-- Sliding Window Log rate limiter, executed atomically via EVAL.
--
-- KEYS[1] = window key, e.g. "rl:sw:{clientId}"
-- ARGV[1] = now_ms (current time in epoch milliseconds)
-- ARGV[2] = window_ms (size of the sliding window, in milliseconds)
-- ARGV[3] = limit (max requests allowed inside the window)
-- ARGV[4] = member (unique id for this request, e.g. "<now_ms>-<uuid>")
--
-- Returns: { allowed (0/1), remaining (integer), retry_after_ms (integer) }
--
-- Implementation: a Redis sorted set where each member is one accepted request and its
-- score is the request timestamp. Every call first evicts entries older than the window,
-- then checks the remaining count -- all inside one atomic script so the "count then add"
-- sequence can't race with another instance's identical sequence.

local key = KEYS[1]
local now_ms = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, '-inf', now_ms - window_ms)

local count = redis.call('ZCARD', key)

if count < limit then
  redis.call('ZADD', key, now_ms, member)
  redis.call('PEXPIRE', key, window_ms)
  return { 1, limit - count - 1, 0 }
else
  local retry_after_ms = window_ms
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  if oldest[2] ~= nil then
    retry_after_ms = (tonumber(oldest[2]) + window_ms) - now_ms
    if retry_after_ms < 0 then
      retry_after_ms = 0
    end
  end
  return { 0, 0, retry_after_ms }
end
