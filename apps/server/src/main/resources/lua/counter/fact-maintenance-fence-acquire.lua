local fenceKey = KEYS[1]
local token = ARGV[1]
local typeReply = redis.call('TYPE', fenceKey)
local fenceType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
if fenceType ~= 'none' and fenceType ~= 'string' then
  return redis.error_reply('counter fact maintenance fence has an invalid Redis type')
end
if not token or token == '' then
  return redis.error_reply('counter fact maintenance fence arguments are invalid')
end
redis.call('SET', fenceKey, token)
return 1
