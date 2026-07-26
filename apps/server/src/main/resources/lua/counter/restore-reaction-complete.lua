local completeKey = KEYS[1]
local fenceKey = KEYS[2]
local completeVersion = ARGV[1]
local fenceTypeReply = redis.call('TYPE', fenceKey)
local fenceType = type(fenceTypeReply) == 'table' and fenceTypeReply['ok'] or fenceTypeReply
if fenceType ~= 'none' and fenceType ~= 'string' then
  return redis.error_reply('counter fact maintenance fence has an invalid Redis type')
end
if redis.call('EXISTS', fenceKey) == 1 then return 0 end
redis.call('SET', completeKey, completeVersion)
return 1
