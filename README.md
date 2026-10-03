# Voucher Redemption: Sliding-Window Rate Limiting & Concurrency (Case Study)

A deliberately small Spring Boot service that **lists vouchers** and lets a user **redeem** them,
with one hard rule:

> A user may redeem **at most 5 vouchers in any rolling 60 minutes**.

The point is not the CRUD. It is to show that I understand *why* this is harder than it looks
(races, boundary effects, two stores that can disagree) and how each piece of the stack
(Redis, Postgres, the JVM) earns its place.

**Stack:** Java 21, Spring Boot 3.3, PostgreSQL 16, Redis 7, Lombok, Maven.

## 1. The problem, conceptually

Four things must hold at once, and each one fails in a different way under load:

| Invariant | How it breaks | Where I defend it |
|---|---|---|
| A user never exceeds 5 redemptions per rolling hour | N parallel requests all read "4 so far" and all proceed (check-then-act race) | Redis atomic Lua script (shared by every instance) |
| A voucher is never oversold | Two users read `remaining = 1` and both decrement | Postgres conditional `UPDATE ... WHERE remaining > 0` + `CHECK (remaining >= 0)` |
| A user redeems a given voucher at most once | A double-click / retry sends the same voucher twice at the same instant; both pass any check done in Java | `UNIQUE (user_id, voucher_id)` in Postgres, plus a cheap pre-check in Java as a fast path only |
| A failed redemption doesn't burn quota | Slot taken in Redis, then the DB write fails | Compensation: `release` the slot (a failing release is logged and ignored, the original error wins) |

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

## 3. Concurrency: where does the guarantee have to live?

The service is meant to run as many identical instances behind a load balancer. That one assumption
decides where every guarantee can live: **anything kept in one instance's memory is invisible to the
other instances, so it cannot be the thing that makes an invariant true.**

Order of operations in `VoucherService.redeem`:

```
pre-check: already redeemed?          // Postgres read, fast path only
token = limiter.tryAcquire(user)      // Redis, atomic Lua
try { tx { decrementStock; insert redemption } }   // Postgres; Redis call kept OUTSIDE the tx
catch { limiter.release(user, token); throw }      // failure to release is logged and ignored
```

### What already works with any number of instances
Each invariant is decided in a store every instance shares:
- rate limit: Redis Lua script (check and record in one atomic step)
- stock: Postgres conditional `UPDATE`, the affected-row count says who won
- once per voucher: the unique constraint, the only place that sees *all* the requests

None of these depend on how many instances exist, which is why the tests stay green regardless.

### Why I did not build a per-user JVM lock
I considered a per-user JVM lock (striped `ReentrantLock`s) to make one user's requests take turns.
Its only real benefit is that a duplicate arriving on the *same* instance waits for the first to commit,
so its pre-check finds the row and it never takes a Redis slot, opens a transaction or touches the stock row.

I decided against it because:
- **It guarantees nothing.** Correctness already comes from Lua, the stock `UPDATE` and the unique constraint.
- **Its benefit shrinks as I scale.** With random load balancing across N instances, two simultaneous
  requests from one user share an instance roughly 1 time in N. It quietly stops doing what it was added for.
- **It has its own costs:** requests wait on each other, unrelated users share a stripe, and the lock is
  held across slow Redis/Postgres calls.

### The problem it would not have solved
A user's duplicates (or any request that later fails) hold Redis slots while they are in flight. If the user
is near the limit, a legitimate request arriving at that moment is refused with 429, although a moment later
(after `release`) there would have been room. Nothing is corrupted, but the decision depended on arrival timing.
This still happens across instances, so **any real fix has to live in the shared stores (Redis / Postgres)**,
not in JVM memory. Candidates, none built: a Postgres advisory lock per user inside the transaction,
a distributed Redis lock (expiry / fencing-token pitfalls), reserve-then-confirm entries in Redis,
or accepting the rare false 429 and returning `Retry-After`.

### Other points I can defend
- **Redis call outside the transaction:** otherwise a slow Redis keeps a Postgres connection checked out
  and exhausts the pool, turning a Redis problem into a database problem.
- **Stock is not per-user**, so no per-user lock could protect it anyway. That is Postgres' job.

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
service/VoucherService               orchestration: pre-check -> limiter -> tx -> compensate
repo/VoucherRepository               atomic stock decrement
schema.sql                           explicit constraints (CHECK, FK, indexes)
test/RedeemConcurrencyTest           the proof: parallel hammering, assertions on invariants
```

## 8. Implementation status

- [ ] Lua script
- [ ] `SlidingWindowRateLimiter` (`tryAcquire`, `release`)
- [ ] `VoucherRepository.decrementStock`, `VoucherService.list/redeem`
- [ ] Exceptions + HTTP mapping (429 / 409)
- [ ] Concurrency tests green (5-of-50, last-voucher, sliding, compensation)
- [ ] Add measured results here (e.g. 50 parallel requests -> exactly 5 pass)

## 9. What I'd do next (not built)

Distributed lock or Redis-only design for multi-instance, idempotency keys, outbox/reconciliation for Redis<->DB drift,
metrics on rejections, per-voucher limits, Testcontainers in CI.
