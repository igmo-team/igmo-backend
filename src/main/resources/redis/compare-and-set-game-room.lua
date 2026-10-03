local currentJson = redis.call('GET', KEYS[1])
if not currentJson then
    return -1
end

local currentState = cjson.decode(currentJson)
if tonumber(currentState.version) ~= tonumber(ARGV[1]) then
    return 0
end

redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')
return 1
