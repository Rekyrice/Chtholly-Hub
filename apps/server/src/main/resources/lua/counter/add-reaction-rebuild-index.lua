local fenceKey = KEYS[1]
local indexKey = KEYS[2]
local token = ARGV[1]

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

local indexType = keyType(indexKey)
if keyType(fenceKey) ~= 'string' or redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
if (indexType ~= 'none' and indexType ~= 'set') or #ARGV < 2 then
  return redis.error_reply('counter reaction rebuild index batch is invalid')
end

local members = {}
for index = 2, #ARGV do
  members[#members + 1] = ARGV[index]
end
return redis.call('SADD', indexKey, unpack(members))
