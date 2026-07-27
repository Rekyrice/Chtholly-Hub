local counterKey = KEYS[1]
local aggregationKey = KEYS[2]
local aggregationIndexKey = KEYS[3]
local fenceKey = KEYS[4]
local epochKey = KEYS[5]
local completeKey = KEYS[6]
local likeIndexKey = KEYS[7]
local likeIndexCountKey = KEYS[8]
local favIndexKey = KEYS[9]
local favIndexCountKey = KEYS[10]

local token = ARGV[1]
local expectedLength = tonumber(ARGV[2])
local fieldSize = tonumber(ARGV[3])
local likeIndex = tonumber(ARGV[4])
local favIndex = tonumber(ARGV[5])
local likeCount = tonumber(ARGV[6])
local favCount = tonumber(ARGV[7])
local nextEpochText = ARGV[8]
local indexSentinel = ARGV[9]
local likeShardCount = tonumber(ARGV[10])
local favShardCount = tonumber(ARGV[11])
local uint32Max = 4294967295

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

if (keyType(counterKey) ~= 'none' and keyType(counterKey) ~= 'string')
      or (keyType(aggregationKey) ~= 'none' and keyType(aggregationKey) ~= 'hash')
      or (keyType(aggregationIndexKey) ~= 'none' and keyType(aggregationIndexKey) ~= 'set')
      or keyType(fenceKey) ~= 'string'
      or (keyType(epochKey) ~= 'none' and keyType(epochKey) ~= 'string')
      or keyType(completeKey) ~= 'none' then
  return redis.error_reply('counter reaction finalization key has an invalid Redis type')
end
if redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end

local nextEpoch = tonumber(nextEpochText)
if expectedLength ~= 20 or fieldSize ~= 4 or likeIndex ~= 1 or favIndex ~= 2
      or not likeCount or not favCount or likeCount < 0 or favCount < 0
      or likeCount > uint32Max or favCount > uint32Max
      or likeCount ~= math.floor(likeCount) or favCount ~= math.floor(favCount)
      or not likeShardCount or not favShardCount
      or likeShardCount < 0 or favShardCount < 0
      or likeShardCount > uint32Max or favShardCount > uint32Max
      or likeShardCount ~= math.floor(likeShardCount)
      or favShardCount ~= math.floor(favShardCount)
      or (likeCount == 0 and likeShardCount ~= 0)
      or (likeCount > 0 and (likeShardCount < 1 or likeShardCount > likeCount))
      or (favCount == 0 and favShardCount ~= 0)
      or (favCount > 0 and (favShardCount < 1 or favShardCount > favCount))
      or not nextEpoch or nextEpoch < 1 or nextEpoch ~= math.floor(nextEpoch)
      or not string.match(nextEpochText, '^%d+$')
      or indexSentinel ~= '@mysql-v1' then
  return redis.error_reply('counter reaction finalization arguments are invalid')
end

local function validateIndex(indexKey, countKey, expectedShardCount)
  if keyType(indexKey) ~= 'set' or keyType(countKey) ~= 'string' then
    return false
  end
  if redis.call('SISMEMBER', indexKey, indexSentinel) ~= 1 then
    return false
  end
  local expectedText = redis.call('GET', countKey)
  if not expectedText or not string.match(expectedText, '^%d+$')
        or (expectedText ~= '0' and string.match(expectedText, '^0')) then
    return false
  end
  return expectedText == tostring(expectedShardCount)
        and redis.call('SCARD', indexKey) == expectedShardCount + 1
end

if not validateIndex(likeIndexKey, likeIndexCountKey, likeShardCount)
      or not validateIndex(favIndexKey, favIndexCountKey, favShardCount) then
  return redis.error_reply('counter reaction rebuilt indexes are incomplete')
end

local raw = redis.call('GET', counterKey)
if not raw or string.len(raw) ~= expectedLength then
  raw = string.rep(string.char(0), expectedLength)
end
local function encoded(value)
  return string.char(
        math.floor(value / 16777216) % 256,
        math.floor(value / 65536) % 256,
        math.floor(value / 256) % 256,
        value % 256)
end
local function replace(value, index, nextValue)
  local offset = index * fieldSize
  return string.sub(value, 1, offset) .. encoded(nextValue)
        .. string.sub(value, offset + fieldSize + 1)
end

raw = replace(raw, likeIndex, likeCount)
raw = replace(raw, favIndex, favCount)
redis.call('SET', counterKey, raw)
redis.call('SET', epochKey, nextEpochText)
redis.call('HDEL', aggregationKey, tostring(likeIndex), tostring(favIndex))
if redis.call('HLEN', aggregationKey) == 0 then
  redis.call('DEL', aggregationKey)
  redis.call('SREM', aggregationIndexKey, aggregationKey)
end
redis.call('SET', fenceKey, '@prepared:' .. token)
return {likeCount, favCount, nextEpoch}
