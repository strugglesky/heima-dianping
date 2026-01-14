package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;



/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }

        Shop shop = this.getById(id);
        return Result.ok(shop);

        /*Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY,
                id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL,TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);*/

    }


    public Shop queryWithPassMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //从redis查询shop缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 缓存命中且不为空值
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //缓存为空值
        if (shopJson != null) {
            return null;
        }
        Shop shop = null;
        String lockKey = null;
        try {
            //获取互斥锁
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            if (!getLock(lockKey)) {
                //获取锁失败
                Thread.sleep(50);
                queryWithPassMutex(id);
                return null;
            }
            //获取锁成功
            //缓存未命中
            shop = this.getById(id);
            //店铺不存在
            if (shop == null) {
                //在redis中设置空对象
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //店铺存在
            String jsonStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key, jsonStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //从redis查询shop缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 缓存命中且不为空值
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //缓存为空值
        if (shopJson != null) {
            return null;
        }

        //缓存未命中
        Shop shop = this.getById(id);
        //店铺不存在
        if (shop == null) {
            //在redis中设置空对象
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //店铺存在
        String jsonStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key, jsonStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Boolean getLock(String lock) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public Boolean unLock(String lock) {
        Boolean flag = stringRedisTemplate.delete(lock);
        return BooleanUtil.isTrue(flag);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        //查询店铺数据
        Shop shop = this.getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //更新数据库
        this.updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(key);
        return null;
    }
}
