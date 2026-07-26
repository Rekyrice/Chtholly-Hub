local bitmapKey = KEYS[1]
local counterKey = KEYS[2]
local fenceKey = KEYS[3]
local indexKey = KEYS[4]
local indexCountKey = KEYS[5]
local bitOffset = tonumber(ARGV[1])
local target = tonumber(ARGV[2])
local metricIndex = tonumber(ARGV[3])
local expectedLength = tonumber(ARGV[4])
local fieldSize = tonumber(ARGV[5])
local indexSentinel = ARGV[6]
local uint32Max = 4294967295

local function keyType(key)
  local reply = redis.call('TYPE', key)
  if type(reply) == 'table' then return reply['ok'] end
  return reply
end

local bitmapType = keyType(bitmapKey)
local counterType = keyType(counterKey)
local fenceType = keyType(fenceKey)
local indexType = keyType(indexKey)
local indexCountType = keyType(indexCountKey)
if (bitmapType ~= 'none' and bitmapType ~= 'string')
      or (counterType ~= 'none' and counterType ~= 'string')
      or (fenceType ~= 'none' and fenceType ~= 'string')
      or (indexType ~= 'none' and indexType ~= 'set')
      or (indexCountType ~= 'none' and indexCountType ~= 'string') then
  return redis.error_reply('counter reaction projection key has an invalid Redis type')
end
if not bitOffset or bitOffset < 0 or bitOffset >= 32768
      or bitOffset ~= math.floor(bitOffset)
      or (target ~= 0 and target ~= 1)
      or (metricIndex ~= 1 and metricIndex ~= 2)
      or expectedLength ~= 20 or fieldSize ~= 4
      or indexSentinel ~= '@mysql-v1' then
  return redis.error_reply('counter reaction projection arguments are invalid')
end
if redis.call('EXISTS', fenceKey) == 1 then
  return {-1, 0}
end

local previous = redis.call('GETBIT', bitmapKey, bitOffset)
local delta = target - previous
local raw = redis.call('GET', counterKey)
local validCounter = raw and string.len(raw) == expectedLength
local nextCount = nil
if validCounter and delta ~= 0 then
  local byteOffset = metricIndex * fieldSize
  local b1, b2, b3, b4 = string.byte(raw, byteOffset + 1, byteOffset + 4)
  local currentCount = ((b1 * 256 + b2) * 256 + b3) * 256 + b4
  nextCount = currentCount + delta
  if nextCount < 0 or nextCount > uint32Max then
    return redis.error_reply('counter reaction projection would overflow unsigned Int32')
  end
end

if delta ~= 0 then
  redis.call('SETBIT', bitmapKey, bitOffset, target)
end
redis.call('SADD', indexKey, indexSentinel)
if redis.call('BITCOUNT', bitmapKey) == 0 then
  redis.call('DEL', bitmapKey)
  redis.call('SREM', indexKey, bitmapKey)
else
  redis.call('SADD', indexKey, bitmapKey)
end
redis.call('SET', indexCountKey, tostring(redis.call('SCARD', indexKey) - 1))

if not validCounter then
  return {2, delta}
end
if delta == 0 then
  return {0, 0}
end

local function encoded(value)
  return string.char(
        math.floor(value / 16777216) % 256,
        math.floor(value / 65536) % 256,
        math.floor(value / 256) % 256,
        value % 256)
end
local byteOffset = metricIndex * fieldSize
local nextRaw = string.sub(raw, 1, byteOffset) .. encoded(nextCount)
      .. string.sub(raw, byteOffset + fieldSize + 1)
redis.call('SET', counterKey, nextRaw)
return {1, delta}
