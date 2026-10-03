-- Sliding window LOG rate limiter (Redis ZSET).
-- KEYS[1] = rl:redeem:{userId}
-- ARGV[1] = window (millis)
-- ARGV[2] = max allowed in window
-- ARGV[3] = unique member for this request (so two requests in the same millis don't collapse)
-- "now" is read from Redis TIME, so all app instances share one clock.
-- Returns: 1 if allowed (and recorded), 0 if rejected.
local key = KEYS[1]
local window = tonumber(ARGV[1])
local max = tonumber(ARGV[2])
local member = ARGV[3]

local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

-- 1. drop entries older than now - window
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
-- 2. count what is left
local count = redis.call('ZCARD', key)
-- 3. under the limit: record this request, refresh TTL so idle users don't leak memory
if count < max then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return 1
end
-- 4. over the limit
return 0
