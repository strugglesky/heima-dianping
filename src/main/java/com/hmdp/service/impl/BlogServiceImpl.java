package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        //判断用户是否为该blog点赞 并给isLiked字段赋值
        queryBlogIsLiked( blog);
        //将博客中的用户id查询用户 并且保存用户信息到到blog中
        queryBlogUser( blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.queryBlogIsLiked(blog);
        });
        return Result.ok(records);
    }

    //点赞
    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断是否已经点赞
        String key = "blog:liked:" + id;
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isLiked)){
            //更新数据库点赞数
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                //保存用户id到redis
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }else {
            //用户已经点赞
            //更新数据库点赞数
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                //保存用户id从redis移除
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    public void queryBlogIsLiked(Blog blog) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(isLiked);
    }

    public void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
