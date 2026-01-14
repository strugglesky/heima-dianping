package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> queryList() {
        //查询缓存中是否存在店铺类型列表
        List<String> shopTypes = stringRedisTemplate.opsForList().range("shopTypes", 0, -1);
        // 缓存存在
        if (shopTypes != null && !shopTypes.isEmpty()) {
            // 正确的方式：逐个将 JSON 字符串转换为 ShopType 对象
            List<ShopType> result = shopTypes.stream()
                    .map(jsonStr -> JSONUtil.toBean(jsonStr, ShopType.class))
                    .collect(Collectors.toList());
            return result;
        }
        //查询数据库
        List<ShopType> list = this.query().orderByAsc("sort").list();
        if (list != null && !list.isEmpty()) {
            System.out.println( "数据库：" + list);
            stringRedisTemplate.delete("shopTypes");
            for(ShopType shopType : list){
                stringRedisTemplate.opsForList().rightPush("shopTypes", JSONUtil.toJsonStr(shopType));
            }
        }
        return list;
    }
}
