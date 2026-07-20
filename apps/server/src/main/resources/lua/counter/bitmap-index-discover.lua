redis.replicate_commands()
local cursorKey = KEYS[1]
local completeKey = KEYS[2]
local candidatesKey = KEYS[3]
local scanCount = tonumber(ARGV[1])
local candidateLimit = tonumber(ARGV[2])
local indexSentinel = ARGV[3]
local maxExactInteger = 9007199254740991
local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end
if (keyType(cursorKey) ~= 'none' and keyType(cursorKey) ~= 'string')
      or (keyType(completeKey) ~= 'none' and keyType(completeKey) ~= 'string')
      or (keyType(candidatesKey) ~= 'none' and keyType(candidatesKey) ~= 'zset') then
  return redis.error_reply('counter Bitmap discovery key has an invalid Redis type')
end
if not scanCount or scanCount < 1 or scanCount > 10000 or scanCount ~= math.floor(scanCount)
      or not candidateLimit or candidateLimit < 0 or candidateLimit > 1000
      or candidateLimit ~= math.floor(candidateLimit) or indexSentinel ~= '@v1' then
  return redis.error_reply('counter Bitmap discovery arguments are invalid')
end
local complete = redis.call('GET', completeKey)
if complete and complete ~= 'v1' then
  return redis.error_reply('counter Bitmap index version is invalid')
end
local cursor = redis.call('GET', cursorKey) or '0'
if not string.match(cursor, '^%d+$') then
  return redis.error_reply('counter Bitmap discovery cursor is invalid')
end
local tail = redis.call('ZREVRANGE', candidatesKey, 0, 0, 'WITHSCORES')
local maxScore = 0
if #tail == 2 then
  maxScore = tonumber(tail[2])
  if not maxScore or maxScore < 0 or maxScore ~= math.floor(maxScore)
        or maxScore >= maxExactInteger then
    return redis.error_reply('counter Bitmap candidate score is invalid')
  end
end

local page = redis.call('SCAN', cursor, 'MATCH', 'bm:*:*:*:*', 'COUNT', scanCount)
local entries = {}
local uniqueMembers = {}
local entityIndexes = {}
local newMemberCount = 0
for _, bitmapKey in ipairs(page[2]) do
  local metric, entityType, entityId, chunk =
        string.match(bitmapKey, '^bm:([^:]+):([^:]+):([^:]+):(%d+)$')
  if (metric == 'like' or metric == 'fav')
        and entityType and string.match(entityType, '^[%w._-]+$')
        and string.len(entityType) <= 32
        and entityId and string.match(entityId, '^[%w._-]+$')
        and string.len(entityId) <= 64
        and (chunk == '0' or not string.match(chunk, '^0'))
        and tonumber(chunk) and tonumber(chunk) <= 281474976710655 then
    if keyType(bitmapKey) ~= 'string' then
      return redis.error_reply('counter Bitmap shard has an invalid Redis type')
    end
    local indexKey = 'bmidx:' .. metric .. ':' .. entityType .. ':' .. entityId
    local likeIndexKey = 'bmidx:like:' .. entityType .. ':' .. entityId
    local favIndexKey = 'bmidx:fav:' .. entityType .. ':' .. entityId
    local likeCountKey = 'bmidxcnt:like:' .. entityType .. ':' .. entityId
    local favCountKey = 'bmidxcnt:fav:' .. entityType .. ':' .. entityId
    if (keyType(likeIndexKey) ~= 'none' and keyType(likeIndexKey) ~= 'set')
          or (keyType(favIndexKey) ~= 'none' and keyType(favIndexKey) ~= 'set')
          or (keyType(likeCountKey) ~= 'none' and keyType(likeCountKey) ~= 'string')
          or (keyType(favCountKey) ~= 'none' and keyType(favCountKey) ~= 'string') then
      return redis.error_reply('counter Bitmap shard index has an invalid Redis type')
    end
    for _, countKey in ipairs({likeCountKey, favCountKey}) do
      local expectedText = redis.call('GET', countKey)
      if expectedText then
        local expected = tonumber(expectedText)
        if not string.match(expectedText, '^%d+$')
              or (expectedText ~= '0' and string.match(expectedText, '^0'))
              or not expected or expected < 0 or expected ~= math.floor(expected)
              or expected >= maxExactInteger then
          return redis.error_reply('counter Bitmap shard index count is invalid')
        end
      end
    end
    table.insert(entries, {indexKey, bitmapKey})
    local member = entityType .. ':' .. entityId
    if not uniqueMembers[member] then
      uniqueMembers[member] = true
      entityIndexes[member] = {likeIndexKey, favIndexKey, likeCountKey, favCountKey}
      if not redis.call('ZSCORE', candidatesKey, member) then
        newMemberCount = newMemberCount + 1
      end
    end
  end
end
if maxScore + newMemberCount >= maxExactInteger then
  return redis.error_reply('counter Bitmap candidate score is exhausted')
end

for _, entry in ipairs(entries) do
  redis.call('SADD', entry[1], entry[2])
end
local function preserveExpectedCount(indexKey, countKey)
  local actual = redis.call('SCARD', indexKey) - 1
  local expectedText = redis.call('GET', countKey)
  if not expectedText then
    redis.call('SET', countKey, tostring(actual))
    return
  end
  local expected = tonumber(expectedText)
  if actual > expected then redis.call('SET', countKey, tostring(actual)) end
end
for _, indexes in pairs(entityIndexes) do
  redis.call('SADD', indexes[1], indexSentinel)
  redis.call('SADD', indexes[2], indexSentinel)
  preserveExpectedCount(indexes[1], indexes[3])
  preserveExpectedCount(indexes[2], indexes[4])
end
for member, _ in pairs(uniqueMembers) do
  if not redis.call('ZSCORE', candidatesKey, member) then
    maxScore = maxScore + 1
    redis.call('ZADD', candidatesKey, maxScore, member)
  end
end
redis.call('SET', cursorKey, page[1])
if page[1] == '0' then
  complete = 'v1'
  redis.call('SET', completeKey, complete)
end

local result = {complete == 'v1' and '1' or '0'}
if complete == 'v1' and candidateLimit > 0 then
  local candidates = redis.call('ZRANGE', candidatesKey, 0, candidateLimit - 1)
  for _, candidate in ipairs(candidates) do table.insert(result, candidate) end
end
return result
