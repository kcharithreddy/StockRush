-- Join the waiting room, or read one's place in it.
-- KEYS[1] = sale:{id}:gate  KEYS[2] = sale:{id}:queue (sorted set)  KEYS[3] = sale:{id}:admitted
-- KEYS[4] = sale:{id}:window  KEYS[5] = sale:{id}:stock
-- ARGV[1] = userId  ARGV[2] = "join" | "status"  ARGV[3] = random lottery score in [0, 1e9) (join only)
-- Returns: -1 no waiting room for this sale | -2 sale not active | -4 sold out | 0 admitted | n > 0 position in line
local window = redis.call('GET', KEYS[4])
if not window then return -2 end
local sep = string.find(window, ',')
local startsAt = tonumber(string.sub(window, 1, sep - 1))
local endsAt = tonumber(string.sub(window, sep + 1))
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now > endsAt then return -2 end
if not redis.call('GET', KEYS[1]) then return -1 end
if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then return 0 end
if tonumber(redis.call('GET', KEYS[5]) or '0') <= 0 then return -4 end
if ARGV[2] == 'join' then
  -- Everyone who joins before the sale opens gets a random place (a lottery: being a millisecond faster,
  -- or a bot, buys nothing). Later arrivals queue behind them in arrival order.
  local score
  -- After opening: milliseconds since the start, with microsecond precision, so arrivals keep their order.
  local nowPrecise = tonumber(time[1]) * 1000 + tonumber(time[2]) / 1000
  if now < startsAt then score = tonumber(ARGV[3]) else score = 1e9 + (nowPrecise - startsAt) end
  redis.call('ZADD', KEYS[2], 'NX', score, ARGV[1])
end
local rank = redis.call('ZRANK', KEYS[2], ARGV[1])
if not rank then return -5 end
return rank + 1
