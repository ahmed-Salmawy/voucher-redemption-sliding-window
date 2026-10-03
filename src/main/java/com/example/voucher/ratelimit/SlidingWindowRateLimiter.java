package com.example.voucher.ratelimit;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Sliding window log limiter on a Redis ZSET: "at most N events in any rolling window".
 * Key per user: rl:redeem:{userId}, score = epoch millis, member = unique token.
 */
@Component
public class SlidingWindowRateLimiter {

    private final StringRedisTemplate redis;
    private final int max;
    private final Duration window;

    public SlidingWindowRateLimiter(StringRedisTemplate redis,
                                    @Value("${voucher.rate-limit.max-redemptions}") int max,
                                    @Value("${voucher.rate-limit.window}") Duration window) {
        this.redis = redis;
        this.max = max;
        this.window = window;
    }

    /**
     * Try to consume one slot.
     *
     * @return the token recorded in the window if allowed (needed for {@link #release}),
     *         empty if the user is over the limit.
     *
     * TODO: load scripts/sliding_window.lua into a DefaultRedisScript<Long> and execute it
     *       with KEYS=[key], ARGV=[nowMillis, windowMillis, max, token(UUID)].
     * TODO: decide where "now" comes from - the app clock or Redis TIME? What breaks with
     *       several app instances whose clocks drift?
     */
    public Optional<String> tryAcquire(String userId) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Give a slot back (ZREM the token). Used when the DB part of a redemption fails after the
     * slot was already taken, so a failed redemption doesn't burn the user's quota.
     * TODO: implement. Is this compensation itself allowed to fail? What then?
     */
    public void release(String userId, String token) {
        throw new UnsupportedOperationException("TODO");
    }
}
