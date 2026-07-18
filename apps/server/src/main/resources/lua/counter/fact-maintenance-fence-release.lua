local fenceKey = KEYS[1]
local token = ARGV[1]
local typeReply = redis.call('TYPE', fenceKey)
local fenceType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
if fenceType == 'none' then return 0 end
if fenceType ~= 'string' then
  return redis.error_reply('counter fact maintenance fence has an invalid Redis type')
end
if redis.call('GET', fenceKey) ~= token then return 0 end
return redis.call('DEL', fenceKey)
