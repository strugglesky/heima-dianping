package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds( time)));
        redisData.setData(value);

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID>  R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis查询shop缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        // 缓存命中且不为空值
        if (StrUtil.isNotBlank(jsonStr)) {
            return JSONUtil.toBean(jsonStr, type);
        }
        //缓存为空值
        if (jsonStr != null) {
            return null;
        }

        //缓存未命中
        R r = dbFallback.apply(id);
        //店铺不存在
        if (r == null) {
            //在redis中设置空对象
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //店铺存在
        this.set(key, r, time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix,String lockPrefix,ID id,Class<R> type,
                                            Function<ID, R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis查询shop缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        // 缓存未命中
        if (StrUtil.isBlank(jsonStr)) {
            return null;
        }
        //缓存命中
        //判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        R r = JSONUtil.toBean((JSONObject)redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期 直接返回缓存数据
            return r;
        }
        //缓存过期 尝试获取锁
        String lockKey = lockPrefix + id;
        Boolean isLock = getLock(lockKey);
        if(isLock){
            //获取锁成功
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R res = dbFallback.apply(id);
                    setWithLogicalExpire(key,res, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });

        }
        //获取锁失败 返回过期数据
        return r;
    }

    public Boolean getLock(String lock) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public Boolean unLock(String lock) {
        Boolean flag = stringRedisTemplate.delete(lock);
        return BooleanUtil.isTrue(flag);
    }


}
