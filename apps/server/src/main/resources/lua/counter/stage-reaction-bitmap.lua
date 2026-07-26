local stageKey = KEYS[1]
local fenceKey = KEYS[2]
local token = ARGV[1]
local ttlSeconds = tonumber(ARGV[2])

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

local stageType = keyType(stageKey)
if (stageType ~= 'none' and stageType ~= 'string')
      or keyType(fenceKey) ~= 'string' then
  return redis.error_reply('counter reaction staging key has an invalid Redis type')
end
if redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
if not ttlSeconds or ttlSeconds < 1 or ttlSeconds ~= math.floor(ttlSeconds)
      or #ARGV < 3 then
  return redis.error_reply('counter reaction staging arguments are invalid')
end

for index = 3, #ARGV do
  local bitOffset = tonumber(ARGV[index])
  if not bitOffset or bitOffset < 0 or bitOffset >= 32768
        or bitOffset ~= math.floor(bitOffset) then
    return redis.error_reply('counter reaction staging bit offset is invalid')
  end
end
for index = 3, #ARGV do
  redis.call('SETBIT', stageKey, tonumber(ARGV[index]), 1)
end
redis.call('EXPIRE', stageKey, ttlSeconds)
return #ARGV - 2
