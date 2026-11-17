package com.example.ragbot.security;

import com.example.ragbot.domain.AppUser;
import com.example.ragbot.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 从 Authorization: Bearer <token> 解析 JWT 并设置安全上下文 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTR = "rag.userId";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                var claims = jwtService.parse(token);
                UUID userId = jwtService.userId(claims);
                // 校验用户仍然存在且启用：清库/删用户后旧 token 会失效，返回 401 让前端重新登录，
                // 避免带着"幽灵 userId"写入数据库触发外键约束错误。
                Optional<AppUser> userOpt = userRepository.findById(userId);
                if (userOpt.isEmpty() || !userOpt.get().enabled()) {
                    chain.doFilter(request, response);
                    return;
                }
                // 角色以数据库实时值为准，避免 token 中的旧角色权限滞后
                String role = userOpt.get().role();
                var auth = new UsernamePasswordAuthenticationToken(
                        userId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
                request.setAttribute(USER_ID_ATTR, userId);
            } catch (Exception ignored) {
                // 无效 token：不设上下文，后续由 Security 拦截返回 401
            }
        }
        chain.doFilter(request, response);
    }
}
