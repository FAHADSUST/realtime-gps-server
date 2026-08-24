-- Atomically store a user's last known location.
--
-- KEYS[1] last-location key   KEYS[2] company geo index
-- ARGV[1] location JSON       ARGV[2] recordedAt (epoch millis)
-- ARGV[3] userId              ARGV[4] ttl seconds (0 = keep forever)
-- ARGV[5] longitude           ARGV[6] latitude
--
-- Returns 1 when stored, 0 when the stored fix is already newer.
--
-- The comparison has to happen inside Redis. Devices buffer while offline and then flush, so points
-- arrive out of order routinely; a read-then-write from the service would also race between its own
-- concurrent requests. Either way the result is the same bug: a user's "last" position jumping
-- backwards in time.

local stored = redis.call('GET', KEYS[1])
if stored then
  local ok, decoded = pcall(cjson.decode, stored)
  if ok and type(decoded) == 'table' and decoded.recordedAt then
    if tonumber(decoded.recordedAt) >= tonumber(ARGV[2]) then
      return 0
    end
  end
end

local ttl = tonumber(ARGV[4])
if ttl > 0 then
  redis.call('SET', KEYS[1], ARGV[1], 'EX', ttl)
else
  redis.call('SET', KEYS[1], ARGV[1])
end

-- GEOADD takes longitude before latitude.
redis.call('GEOADD', KEYS[2], ARGV[5], ARGV[6], ARGV[3])

return 1
