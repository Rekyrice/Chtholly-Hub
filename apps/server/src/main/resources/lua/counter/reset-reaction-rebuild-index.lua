local fenceKey = KEYS[1]
local indexKey = KEYS[2]
local countKey = KEYS[3]
local token = ARGV[1]
local sentinel = ARGV[2]

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

if keyType(fenceKey) ~= 'string' or redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
if not sentinel or sentinel == '' then
  return redis.error_reply('counter reaction rebuild index sentinel is invalid')
end
redis.call('DEL', indexKey, countKey)
redis.call('SADD', indexKey, sentinel)
return 1
