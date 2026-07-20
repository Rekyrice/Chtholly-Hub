local candidatesKey = KEYS[1]
local member = ARGV[1]
local maxExactInteger = 9007199254740991
local typeReply = redis.call('TYPE', candidatesKey)
local keyType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
if keyType == 'none' then return 0 end
if keyType ~= 'zset' then
  return redis.error_reply('counter Bitmap candidates have an invalid Redis type')
end
if not member or member == '' or not redis.call('ZSCORE', candidatesKey, member) then return 0 end
local tail = redis.call('ZREVRANGE', candidatesKey, 0, 0, 'WITHSCORES')
local maxScore = tonumber(tail[2])
if not maxScore or maxScore < 0 or maxScore ~= math.floor(maxScore)
      or maxScore >= maxExactInteger then
  return redis.error_reply('counter Bitmap candidate score is invalid')
end
redis.call('ZADD', candidatesKey, 'XX', maxScore + 1, member)
return 1
