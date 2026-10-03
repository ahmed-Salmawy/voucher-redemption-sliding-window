# Voucher Redemption: Sliding-Window Rate Limiting & Concurrency (Case Study)

A deliberately small Spring Boot service that **lists vouchers** and lets a user **redeem** them,
with one hard rule:

> A user may redeem **at most 5 vouchers in any rolling 60 minutes**.

The point is not the CRUD. It is to show that I understand *why* this is harder than it looks
(races, boundary effects, two stores that can disagree) and how each piece of the stack
(Redis, Postgres, the JVM) earns its place.

**Stack:** Java 21, Spring Boot 3.3, PostgreSQL 16, Redis 7, Lombok, Maven.

## 1. The problem, conceptually

Three things must hold at once, and each one fails in a different way under load:

| Invariant | How it breaks | Where I defend it |
|---|---|---|
| A user never exceeds 5 redemptions per rolling hour | N parallel requests all read "4 so far" and all proceed (check-then-act race) | Redis atomic script + per-user JVM lock |
| A voucher is never oversold | Two users read `remaining = 1` and both decrement | Postgres conditional `UPDATE ... WHERE remaining > 0` + `CHECK (remaining >= 0)` |
| A failed redemption doesn't burn quota | Slot taken in Redis, then the DB write fails | Compensation: `release` the slot |

## 2. Why a *sliding* window

| Algorithm | Behaviour | Problem here |
|---|---|---|
| Fixed window counter (`INCR` + `EXPIRE` per clock hour) | Cheap | **Boundary burst:** 5 at 10:59 + 5 at 11:00 = 10 in two minutes, while "5 per hour" was honoured per bucket |
| Token bucket | Smooth refill, allows bursts up to capacity | Models a *rate*, not the hard cap "5 in any hour" |
| **Sliding window log** (chosen) | Exact: counts events in `(now - 1h, now]` | Memory O(limit) per user, which is only 5 entries here, so the usual downside is irrelevant |
| Sliding window counter (weighted two buckets) | O(1) memory, approximate | Approximation is unacceptable for a hard business cap of 5 |

Because the limit is tiny (5), the exact log is the right trade-off. For a limit of 10k/hour I
would switch to the weighted counter and accept the approximation.

### Redis data model
- Key `rl:redeem:{userId}`, a **ZSET**; score = event time (epoch ms), member = unique token (UUID).
- Unique member matters: two requests in the same millisecond must not collapse into one entry.
- `PEXPIRE key window` on every write so idle users cost zero memory.

### Algorithm (one Lua script, see `scripts/sliding_window.lua`)
1. `ZREMRANGEBYSCORE key -inf (now - window)`
2. `ZCARD key`
3. if `< 5`: `ZADD key now token` + `PEXPIRE`, return allowed; else return rejected

**Why Lua:** Redis runs a script atomically, so no other client can slip between the count and the
add. Four separate client calls reintroduce the exact race we are trying to remove (a `MULTI/EXEC`
can't help either, since the `ZCARD` result must influence the `ZADD`).

## 3. Concurrency: the JVM layer

`UserLockRegistry` gives **per-user mutual exclusion inside one JVM** using striped `ReentrantLock`s
(fixed array, `hash(userId) % 256`; bounded memory, no eviction problem).

Order of operations in `VoucherService.redeem`:

```
lock(user) {
    token = limiter.tryAcquire(user)        // Redis, atomic
    try { tx { decrementStock; insert redemption } }   // Postgres, commit happens INSIDE the lock
    catch { limiter.release(user, token); throw }
}
```

Design points I want to be able to defend:

- **The lock must wrap the transaction, not the other way round.** With `@Transactional` on the
  method, commit happens after return, i.e. after the lock is released; another thread can enter
  before the first one's data is visible. Hence `TransactionTemplate`.
- **Is the JVM lock redundant given the atomic Lua script?** For the rate limit alone, yes: the script
  is already linearizable. The lock adds value by serializing the *whole* acquire -> DB -> compensate
  sequence per user, so that the compensation in the failure path can't interleave with a concurrent
  attempt for the same user, and it reduces wasted Redis/DB round trips from hot users.
  I keep it because the brief is to handle concurrency at JVM level, and I document that it is
  an optimisation + sequencing guard, not the source of correctness.
- **Limits of a JVM lock:** it protects one process. Behind a load balancer with N instances, two
  requests from the same user can land on different JVMs. Correctness there comes from the layers
  below (Redis script for the rate limit, Postgres row update for stock). A distributed lock
  (Redisson / `SET NX PX`) would be the next step, with its own failure modes (expiry while holding, fencing tokens).
- **Stock is not protected by the JVM lock** (it's per *user*, stock is contended across users). That
  is Postgres' job: `UPDATE voucher SET remaining = remaining - 1 WHERE id = ? AND remaining > 0`
  is atomic and tells me via the affected-row count whether I won.

## 4. Failure modes & trade-offs (to discuss)

| Scenario | Behaviour | Note |
|---|---|---|
| DB write fails after slot taken | `release` removes the token | If `release` also fails (Redis down), the user loses a slot until it expires, which is the safe direction (fail closed) |
| App crashes between Redis and DB | Slot consumed, no redemption row | Same, bounded to 1 hour. A reconciliation job against `redemption` could repair it |
| Redis is down | Redemption rejected (fail closed) | Alternative is fail open + DB count as fallback; chosen based on "business cap is hard" |
| Clock drift across app instances | Window edges skew by the drift | Use Redis `TIME` inside the script to have one clock |
| Duplicate request retry (client timeout) | Would consume two slots | Out of scope; idempotency key would fix it |

## 5. API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/vouchers` | Vouchers with stock left |
| `POST` | `/vouchers/{id}/redeem` | Header `X-User-Id`. `200` ok, `429` + `Retry-After` over limit, `409` sold out |

Auth, pagination, voucher creation/admin endpoints are intentionally **out of scope** (reduced scope case study).

## 6. Run it

```bash
docker compose up -d        # postgres + redis
mvn spring-boot:run
curl localhost:8080/vouchers
curl -X POST -H 'X-User-Id: alice' localhost:8080/vouchers/1/redeem
```

## 7. Project layout

```
ratelimit/SlidingWindowRateLimiter   Redis ZSET + Lua, tryAcquire / release
resources/scripts/sliding_window.lua the atomic script
concurrency/UserLockRegistry         striped per-user locks
service/VoucherService               orchestration: lock -> limiter -> tx -> compensate
repo/VoucherRepository               atomic stock decrement
schema.sql                           explicit constraints (CHECK, FK, indexes)
test/RedeemConcurrencyTest           the proof: parallel hammering, assertions on invariants
```

## 8. Implementation status

- [ ] Lua script
- [ ] `SlidingWindowRateLimiter` (`tryAcquire`, `release`)
- [ ] `UserLockRegistry.withLock`
- [ ] `VoucherRepository.decrementStock`, `VoucherService.list/redeem`
- [ ] Exceptions + HTTP mapping (429 / 409)
- [ ] Concurrency tests green (5-of-50, last-voucher, sliding, compensation)
- [ ] Add measured results here (e.g. 50 parallel requests -> exactly 5 pass)

## 9. What I'd do next (not built)

Distributed lock or Redis-only design for multi-instance, idempotency keys, outbox/reconciliation for Redis<->DB drift,
metrics on rejections, per-voucher limits, Testcontainers in CI.
