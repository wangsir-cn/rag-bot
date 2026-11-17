package com.example.ragbot.security;

import com.example.ragbot.domain.AppUser;
import com.example.ragbot.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 从请求属性解析当前登录用户（供 controller/service 使用） */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UUID id() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("未登录");
        }
        return UUID.fromString(auth.getPrincipal().toString());
    }

    public AppUser get() {
        return userRepository.findById(id()).orElseThrow(() -> new IllegalStateException("用户不存在"));
    }
}
