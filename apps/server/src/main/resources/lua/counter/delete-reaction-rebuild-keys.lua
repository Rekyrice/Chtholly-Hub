local fenceKey = KEYS[1]
local token = ARGV[1]

local typeReply = redis.call('TYPE', fenceKey)
local fenceType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
if fenceType ~= 'string' or redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
if #KEYS < 2 then
  return redis.error_reply('counter reaction rebuild delete batch is empty')
end

local keys = {}
for index = 2, #KEYS do
  keys[#keys + 1] = KEYS[index]
end
return redis.call('DEL', unpack(keys))
