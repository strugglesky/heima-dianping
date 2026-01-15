package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.security.PermitAll;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService followService;
    @GetMapping("{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followedUserId,@PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followedUserId,isFollow);
    }

    @GetMapping("or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followedUserId) {
        return followService.isFollow(followedUserId);
    }

    @GetMapping("common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}
