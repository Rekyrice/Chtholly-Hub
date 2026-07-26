local completeKey = KEYS[1]
local indexKey = KEYS[2]
local indexCountKey = KEYS[3]
local bitmapKey = KEYS[4]
local completeVersion = ARGV[1]
local indexSentinel = ARGV[2]
local bitmapPrefix = ARGV[3]
local bitOffset = tonumber(ARGV[4])

local function keyType(key)
  local reply = redis.call('TYPE', key)
  if type(reply) == 'table' then return reply['ok'] end
  return reply
end

if redis.call('GET', completeKey) ~= completeVersion then return -1 end
if keyType(indexKey) ~= 'set' or keyType(indexCountKey) ~= 'string' then return -1 end
if redis.call('SISMEMBER', indexKey, indexSentinel) ~= 1 then return -1 end
local expectedText = redis.call('GET', indexCountKey)
if not expectedText or not string.match(expectedText, '^%d+$')
      or (expectedText ~= '0' and string.match(expectedText, '^0')) then
  return -1
end
local expected = tonumber(expectedText)
local members = redis.call('SMEMBERS', indexKey)
if not expected or expected ~= #members - 1 then return -1 end
for _, member in ipairs(members) do
  if member ~= indexSentinel then
    if string.sub(member, 1, string.len(bitmapPrefix)) ~= bitmapPrefix
          or keyType(member) ~= 'string' then
      return -1
    end
  end
end
local bitmapType = keyType(bitmapKey)
local indexed = redis.call('SISMEMBER', indexKey, bitmapKey) == 1
if bitmapType == 'none' and not indexed then return 0 end
if bitmapType ~= 'string' or not indexed then return -1 end
if not bitOffset or bitOffset < 0 or bitOffset >= 32768
      or bitOffset ~= math.floor(bitOffset) then
  return -1
end
return redis.call('GETBIT', bitmapKey, bitOffset)
