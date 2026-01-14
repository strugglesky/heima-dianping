package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    public ShopServiceImpl shopService;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Test
    public void test01(){
        shopService.saveShop2Redis(1L, 10L);
    }
    @Test
    public void test02(){
        //数据预热
        shopService.saveShop2Redis(2L, 20L);
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY,
                2L, Shop.class, shopService::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null) {
            System.out.println("店铺不存在");
        }
        System.out.println(shop);
    }
    @Test
    public void test03(){
        long id = redisIdWorker.nextId("order");
        System.out.println(id);
        //0111 1001 0101 1001 0000 1111 1000 0000 0000 0000 0000 0000 0000 0000 0001
        //01111001 01011001 00001111 10000000 000000000 000000000 0000000001
    }

}
