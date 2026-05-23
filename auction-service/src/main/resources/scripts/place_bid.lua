-- place_bid.lua
-- Atomically validates and records a bid in Redis.
--
-- KEYS[1]  auction hash        "auction:{id}"
-- KEYS[2]  winners sorted set  "auction:{id}:winners"
-- ARGV[1]  bid amount (string)
-- ARGV[2]  bidder id
--
-- Returns array: {version, bidCount, prevBidder, prevAmount, newFloor, topBidder}
--   prevBidder / prevAmount : bidder knocked out of winners (empty / "0" if none)
--   newFloor                : minimum bid needed to enter winners after this bid
--   topBidder               : bidder with the highest amount currently in winners set

local hashKey    = KEYS[1]
local winnersKey = KEYS[2]
local amount     = tonumber(ARGV[1])
local bidderId   = ARGV[2]

-- 1. Auction must be open
local status = redis.call('HGET', hashKey, 'status')
if status ~= 'OPEN' then
    return redis.error_reply('AUCTION_CLOSED')
end

-- 2. Bid must exceed current threshold (floor when full, startingBid when not)
local threshold = tonumber(redis.call('HGET', hashKey, 'current_highest'))
if amount <= threshold then
    return redis.error_reply('BID_TOO_LOW:' .. tostring(threshold))
end

-- 3. Bid must not exceed maxPrice (0 means no ceiling)
local maxPrice = tonumber(redis.call('HGET', hashKey, 'max_price'))
if maxPrice and maxPrice > 0 and amount > maxPrice then
    return redis.error_reply('PRICE_TOO_HIGH:' .. tostring(maxPrice))
end

-- 4. Check capacity
local quantity    = tonumber(redis.call('HGET', hashKey, 'quantity'))
local winnerCount = tonumber(redis.call('ZCARD', winnersKey))

local prevBidder = ''
local prevAmount = 0

if winnerCount >= quantity then
    -- At capacity: evict the current floor bidder
    local floor = redis.call('ZRANGE', winnersKey, 0, 0, 'WITHSCORES')
    prevBidder  = floor[1]
    prevAmount  = tonumber(floor[2])
    redis.call('ZREM', winnersKey, prevBidder)
end

-- 4. Add the new winner
redis.call('ZADD', winnersKey, amount, bidderId)

-- 5. Recalculate new floor
local newWinnerCount = tonumber(redis.call('ZCARD', winnersKey))
local newFloor
if newWinnerCount >= quantity then
    local lowest = redis.call('ZRANGE', winnersKey, 0, 0, 'WITHSCORES')
    newFloor = tonumber(lowest[2])
else
    newFloor = threshold  -- still below capacity, threshold unchanged
end

-- 6. Get the actual top bidder (highest score)
local top      = redis.call('ZREVRANGE', winnersKey, 0, 0)
local topBidder = top[1]

-- 7. Update hash atomically
local version  = tonumber(redis.call('HGET', hashKey, 'version'))   + 1
local bidCount = tonumber(redis.call('HGET', hashKey, 'bid_count')) + 1

redis.call('HMSET', hashKey,
    'current_highest', tostring(newFloor),
    'version',         tostring(version),
    'bid_count',       tostring(bidCount))

-- 8. Read full winners snapshot atomically (no gap for concurrent bids to interfere)
local winnersRaw = redis.call('ZRANGE', winnersKey, 0, -1, 'WITHSCORES')

return {tostring(version), tostring(bidCount), prevBidder, tostring(prevAmount), tostring(newFloor), topBidder, unpack(winnersRaw)}
