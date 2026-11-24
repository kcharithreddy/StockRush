-- KEYS[1] = sale:{id}:stock   KEYS[2] = sale:{id}:buyers   KEYS[3] = sale:{id}:window
-- ARGV[1] = userId            ARGV[2] = now (epoch millis)
-- Returns: 1 success | 0 sold out | -1 already bought | -2 sale not active
local window = redis.call('GET', KEYS[3])
if not window then return -2 end
local sep = string.find(window, ',')
local startsAt = tonumber(string.sub(window, 1, sep - 1))
local endsAt = tonumber(string.sub(window, sep + 1))
local now = tonumber(ARGV[2])
if now < startsAt or now > endsAt then return -2 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then return 0 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
