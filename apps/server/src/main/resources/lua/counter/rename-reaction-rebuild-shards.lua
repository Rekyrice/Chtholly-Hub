local fenceKey = KEYS[1]
local token = ARGV[1]

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

if keyType(fenceKey) ~= 'string' or redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
if #KEYS < 3 or (#KEYS - 1) % 2 ~= 0 then
  return redis.error_reply('counter reaction rebuild rename batch is invalid')
end
for index = 2, #KEYS, 2 do
  if keyType(KEYS[index]) ~= 'string' then
    return redis.error_reply('counter reaction rebuild staging shard is missing')
  end
end
for index = 2, #KEYS, 2 do
  redis.call('RENAME', KEYS[index], KEYS[index + 1])
end
return (#KEYS - 1) / 2
