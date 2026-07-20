local cntKey = KEYS[1]
local aggKey = KEYS[2]
local aggIndexKey = KEYS[3]
local fenceKey = KEYS[4]
local epochKey = KEYS[5]
local token = ARGV[1]
local expectedLength = tonumber(ARGV[2])
local fieldSize = tonumber(ARGV[3])
local likeIndex = tonumber(ARGV[4])
local favIndex = tonumber(ARGV[5])
local likeCount = tonumber(ARGV[6])
local favCount = tonumber(ARGV[7])
local uint32Max = 4294967295

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end
if (keyType(cntKey) ~= 'none' and keyType(cntKey) ~= 'string')
      or (keyType(aggKey) ~= 'none' and keyType(aggKey) ~= 'hash')
      or (keyType(aggIndexKey) ~= 'none' and keyType(aggIndexKey) ~= 'set')
      or keyType(fenceKey) ~= 'string'
      or (keyType(epochKey) ~= 'none' and keyType(epochKey) ~= 'string') then
  return redis.error_reply('counter reconciliation key has an invalid Redis type')
end
if redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reconciliation fence ownership lost')
end
if expectedLength ~= 20 or fieldSize ~= 4 or likeIndex ~= 1 or favIndex ~= 2
      or not likeCount or not favCount or likeCount < 0 or favCount < 0
      or likeCount > uint32Max or favCount > uint32Max
      or likeCount ~= math.floor(likeCount) or favCount ~= math.floor(favCount) then
  return redis.error_reply('counter reconciliation arguments are invalid')
end
local raw = redis.call('GET', cntKey)
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
local epochText = redis.call('GET', epochKey) or '0'
local epoch = tonumber(epochText)
if not epoch or epoch < 0 or epoch ~= math.floor(epoch) then
  return redis.error_reply('counter fact epoch is invalid')
end
local nextEpoch = redis.call('INCR', epochKey)
redis.call('SET', cntKey, raw)
redis.call('HDEL', aggKey, tostring(likeIndex), tostring(favIndex))
if redis.call('HLEN', aggKey) == 0 then
  redis.call('DEL', aggKey)
  redis.call('SREM', aggIndexKey, aggKey)
end
return {likeCount, favCount, nextEpoch}
