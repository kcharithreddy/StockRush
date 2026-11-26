-- Lets the next batch of the waiting room in. Every sale-service instance calls this each second; the
-- per-second tick key makes sure only the first caller in a given second admits anyone.
-- KEYS[1] = sale:{id}:gate  KEYS[2] = sale:{id}:queue  KEYS[3] = sale:{id}:admitted  KEYS[4] = sale:{id}:window
-- KEYS[5] = sale:{id}:stock  KEYS[6] = sale:{id}:admit:{epochSecond}
-- ARGV[1] = batch size
-- Returns the number of buyers admitted.
if not redis.call('GET', KEYS[1]) then return 0 end
local window = redis.call('GET', KEYS[4])
if not window then return 0 end
local sep = string.find(window, ',')
local startsAt = tonumber(string.sub(window, 1, sep - 1))
local endsAt = tonumber(string.sub(window, sep + 1))
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now < startsAt or now > endsAt then return 0 end
-- Nothing left to sell: keep people where they are; their status turns to SOLD_OUT.
if tonumber(redis.call('GET', KEYS[5]) or '0') <= 0 then return 0 end
if redis.call('INCR', KEYS[6]) > 1 then return 0 end
redis.call('EXPIRE', KEYS[6], 5)
local popped = redis.call('ZPOPMIN', KEYS[2], tonumber(ARGV[1]))
local admitted = 0
for i = 1, #popped, 2 do
  redis.call('SADD', KEYS[3], popped[i])
  admitted = admitted + 1
end
return admitted
