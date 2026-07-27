local fenceKey = KEYS[1]
local bitmapKey = KEYS[2]
local indexKey = KEYS[3]
local token = ARGV[1]

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

local bitmapType = keyType(bitmapKey)
if keyType(fenceKey) ~= 'string' or redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
if (bitmapType ~= 'none' and bitmapType ~= 'string')
      or keyType(indexKey) ~= 'set'
      or #ARGV < 2 then
  return redis.error_reply('counter reaction rebuild bitmap batch is invalid')
end
for index = 2, #ARGV do
  local bitOffset = tonumber(ARGV[index])
  if not bitOffset or bitOffset < 0 or bitOffset >= 32768
        or bitOffset ~= math.floor(bitOffset) then
    return redis.error_reply('counter reaction rebuild bit offset is invalid')
  end
end
for index = 2, #ARGV do
  redis.call('SETBIT', bitmapKey, tonumber(ARGV[index]), 1)
end
redis.call('SADD', indexKey, bitmapKey)
return #ARGV - 1
