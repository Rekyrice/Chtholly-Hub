local counterKey = KEYS[1]
local completeKey = KEYS[2]
local completeVersion = ARGV[1]
local expectedLength = tonumber(ARGV[2])
local fieldSize = tonumber(ARGV[3])
local schemaLength = tonumber(ARGV[4])

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

if completeVersion ~= '@mysql-v1'
      or expectedLength ~= 20
      or fieldSize ~= 4
      or schemaLength ~= 5 then
  return redis.error_reply('counter reaction count read arguments are invalid')
end

local counterType = keyType(counterKey)
if counterType ~= 'string' then
  return {-1}
end
local raw = redis.call('GET', counterKey)
if not raw or string.len(raw) ~= expectedLength then
  return {-1}
end

local completeType = keyType(completeKey)
local result = {
  completeType == 'string'
      and redis.call('GET', completeKey) == completeVersion
      and 1
      or 0
}
for index = 0, schemaLength - 1 do
  local offset = index * fieldSize
  local b1, b2, b3, b4 = string.byte(raw, offset + 1, offset + fieldSize)
  result[#result + 1] = ((b1 * 256 + b2) * 256 + b3) * 256 + b4
end
return result
