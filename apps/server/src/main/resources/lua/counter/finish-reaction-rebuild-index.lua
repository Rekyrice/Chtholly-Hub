local fenceKey = KEYS[1]
local indexKey = KEYS[2]
local countKey = KEYS[3]
local token = ARGV[1]
local expectedText = ARGV[2]
local sentinel = ARGV[3]

local function keyType(key)
  local reply = redis.call('TYPE', key)
  return type(reply) == 'table' and reply['ok'] or reply
end

if keyType(fenceKey) ~= 'string' or redis.call('GET', fenceKey) ~= token then
  return redis.error_reply('counter reaction rebuild fence ownership lost')
end
if keyType(indexKey) ~= 'set'
      or (keyType(countKey) ~= 'none' and keyType(countKey) ~= 'string')
      or not expectedText or not string.match(expectedText, '^%d+$')
      or not sentinel or sentinel == '' then
  return redis.error_reply('counter reaction rebuild index finalization is invalid')
end
local expected = tonumber(expectedText)
if not expected or expected ~= redis.call('SCARD', indexKey) - 1
      or redis.call('SISMEMBER', indexKey, sentinel) ~= 1 then
  return redis.error_reply('counter reaction rebuild index is incomplete')
end
redis.call('SET', countKey, expectedText)
return expected
