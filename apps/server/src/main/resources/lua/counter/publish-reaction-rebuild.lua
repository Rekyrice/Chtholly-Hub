local fenceKey = KEYS[1]
local completeKey = KEYS[2]
local token = ARGV[1]
local completeVersion = ARGV[2]

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

if keyType(fenceKey) ~= 'string'
      or redis.call('GET', fenceKey) ~= '@prepared:' .. token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
local completeType = keyType(completeKey)
if (completeType ~= 'none' and completeType ~= 'string')
      or completeVersion ~= '@mysql-v1' then
  return redis.error_reply('counter reaction rebuilt projection publication is invalid')
end
redis.call('SET', completeKey, completeVersion)
redis.call('DEL', fenceKey)
return 1
