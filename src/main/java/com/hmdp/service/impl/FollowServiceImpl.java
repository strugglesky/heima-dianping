package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /*
    * 关注别人
    * */
    @Override
    public Result follow(Long followedUserId, Boolean isFollow) {
        //1.查询当前用户
        Long userId = UserHolder.getUser().getId();
        if(isFollow){
            //已经关注 进行取关
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followedUserId));
            if (isSuccess){
                //从redis中移除set集合中的followedUserId
                stringRedisTemplate.opsForSet().remove("follows:" + userId, followedUserId.toString());
            }
        }
        //未关注 进行关注
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(followedUserId);
        boolean isSuccess = save(follow);
        if (isFollow){
            //将关注数据保存到redis的set结构中
            stringRedisTemplate.opsForSet().add("follows:" + userId, followedUserId.toString());
        }
        return Result.ok();
    }

    /*
    * 查询是否关注他人
    * */

    @Override
    public Result isFollow(Long followedUserId) {
        //1.查询当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否存在关注数据
        Integer count = lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followedUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //查询当前用户id
        Long userId = UserHolder.getUser().getId();
        //当前用户key
        String key1 = "follows:" + userId;
        //所查询用户的key
        String key2 = "follows:" + id;
        //查询两个人的关注交集
        Set<String> commons = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (commons == null || commons.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> userDTOS = commons.stream().map(s -> {
            User user = userService.getById(Long.valueOf( s));
            return BeanUtil.copyProperties(user, UserDTO.class);
        }).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
