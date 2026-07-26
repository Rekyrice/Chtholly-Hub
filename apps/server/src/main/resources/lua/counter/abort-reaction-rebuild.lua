local fenceKey = KEYS[1]
local completeKey = KEYS[2]
local token = ARGV[1]

local typeReply = redis.call('TYPE', fenceKey)
local fenceType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
if fenceType ~= 'none' and fenceType ~= 'string' then
  return redis.error_reply('counter reaction rebuild fence has an invalid Redis type')
end
local fenceValue = fenceType == 'string' and redis.call('GET', fenceKey) or nil
if fenceValue ~= token
      and fenceValue ~= '@prepared:' .. token
      and fenceValue ~= '@dirty:' .. token then
  return 0
end
redis.call('DEL', completeKey)
redis.call('DEL', fenceKey)
return 1
