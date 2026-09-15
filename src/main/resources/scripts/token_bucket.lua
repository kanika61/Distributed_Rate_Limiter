-- Token Bucket rate limiter, executed atomically via EVAL.
--
-- KEYS[1] = bucket key, e.g. "rl:tb:{clientId}"
-- ARGV[1] = capacity (max burst size, integer)
-- ARGV[2] = refill_rate_per_second (tokens added per second, float)
-- ARGV[3] = now_ms (current time in epoch milliseconds, provided by the caller so all
--           app instances agree on "now" relative to Redis, not their own clock)
-- ARGV[4] = requested (tokens this request needs, normally 1)
--
-- Returns: { allowed (0/1), remaining_tokens (integer, floored), retry_after_ms (integer) }
--
-- The bucket is stored as a Redis hash { tokens, ts } so the read-refill-write cycle
-- happens inside a single Lua invocation. Redis executes scripts single-threadedly, so no
-- other client can observe or mutate this key between the read and the write -- that is
-- what makes this safe under concurrent requests from multiple app instances.

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local last_ts = tonumber(bucket[2])

if tokens == nil then
  tokens = capacity
  last_ts = now_ms
end

-- Refill based on elapsed time since the last observed write.
local elapsed_ms = now_ms - last_ts
if elapsed_ms < 0 then
  elapsed_ms = 0
end
local refill = (elapsed_ms / 1000.0) * refill_rate
tokens = math.min(capacity, tokens + refill)

local allowed = 0
local retry_after_ms = 0

if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  local deficit = requested - tokens
  if refill_rate > 0 then
    retry_after_ms = math.ceil((deficit / refill_rate) * 1000.0)
  else
    retry_after_ms = -1 -- refill rate is 0, will never refill
  end
end

redis.call('HMSET', key, 'tokens', tostring(tokens), 'ts', tostring(now_ms))

-- Let the key expire once the bucket would be fully idle for a while, so clients that
-- stop sending traffic don't leave keys in Redis forever.
local ttl_seconds = 60
if refill_rate > 0 then
  ttl_seconds = math.max(60, math.ceil(capacity / refill_rate) * 2)
end
redis.call('EXPIRE', key, ttl_seconds)

return { allowed, math.floor(tokens), retry_after_ms }
