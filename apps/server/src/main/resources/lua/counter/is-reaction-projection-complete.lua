local completeKey = KEYS[1]
local completeVersion = ARGV[1]

local typeReply = redis.call('TYPE', completeKey)
local completeType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
if completeType ~= 'string' then return 0 end
return redis.call('GET', completeKey) == completeVersion and 1 or 0
