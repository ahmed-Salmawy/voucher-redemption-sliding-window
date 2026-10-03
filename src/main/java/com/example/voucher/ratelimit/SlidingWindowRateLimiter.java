package com.example.voucher.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sliding window log limiter on a Redis ZSET: "at most N events in any rolling window".
 * Key per user: rl:redeem:{userId}, score = epoch millis, member = unique token.
 */
@Component
@Slf4j
public class SlidingWindowRateLimiter {

    private final StringRedisTemplate redis;
    private final int max;
    private final Duration window;
    private final DefaultRedisScript<Long> script;
    private final String KEY_FORMAT = "rl:redeem:%s";

    public SlidingWindowRateLimiter(StringRedisTemplate redis,
                                    @Value("${voucher.rate-limit.max-redemptions}") int max,
                                    @Value("${voucher.rate-limit.window}") Duration window) {
        this.redis = redis;
        this.max = max;
        this.window = window;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(
                new ClassPathResource("scripts/sliding_window.lua")
        );
        this.script.setResultType(Long.class);


    }

    /**
     * Try to consume one slot.
     *
     * @return the token recorded in the window if allowed (needed for {@link #release}),
     * empty if the user is over the limit.
     * <p>
     * TODO: load scripts/sliding_window.lua into a DefaultRedisScript<Long> and execute it
     *       with KEYS=[key], ARGV=[nowMillis, windowMillis, max, token(UUID)].
     * TODO: decide where "now" comes from - the app clock or Redis TIME? What breaks with
     *       several app instances whose clocks drift?
     */
    public Optional<String> tryAcquire(String userId) {
        var token = UUID.randomUUID().toString();
        Long result = redis.execute(
                script,
                List.of(KEY_FORMAT.formatted(userId)),
                String.valueOf(window.toMillis()),
                String.valueOf(max),
                token
        );
        return Long.valueOf(1).equals(result) ? Optional.of(token) : Optional.empty();

    }

    /**
     * Give a slot back (ZREM the token). Used when the DB part of a redemption fails after the
     * slot was already taken, so a failed redemption doesn't burn the user's quota.
     *
     */
    public void release(String userId, String token) {
        try {

            var key = KEY_FORMAT.formatted(userId);
            var updated = redis.opsForZSet().remove(key, token);
            log.debug("Removed token {} ,from user {}, count is  {}", token, key, updated);
        } catch (Exception e) {
            log.error("failed to remove token {} for user{} cause", e, token, userId);
        }
    }
}
