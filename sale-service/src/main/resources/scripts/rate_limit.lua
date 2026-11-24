-- KEYS[1] = rl:{userId}:{epochSecond}   ARGV[1] = TTL in seconds
-- Returns the request count for this window. INCR and EXPIRE run atomically,
-- so a counter can never be left behind without a TTL.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
