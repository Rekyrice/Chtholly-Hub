local fenceKey = KEYS[1]
local completeKey = KEYS[2]
local token = ARGV[1]

if not token or token == '' then
  return redis.error_reply('counter reaction rebuild token is invalid')
end
local typeReply = redis.call('TYPE', fenceKey)
local fenceType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
if fenceType ~= 'none' and fenceType ~= 'string' then
  redis.call('DEL', completeKey)
  redis.call('DEL', fenceKey)
end

redis.call('SET', fenceKey, token)
redis.call('DEL', completeKey)
return 1
