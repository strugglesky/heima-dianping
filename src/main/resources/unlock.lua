-- 锁的key
local key = KEYS[1]
-- 当前线程id
local threadId = ARGV[1]

-- 获取锁中的线程提示 get key
local id = redis.call("get", key)
-- 比较锁中的线程id和当前线程id是否相同
if(id == threadId) then
    -- 相同则删除锁 del key
    return redis.call("del", key)
end
return 0