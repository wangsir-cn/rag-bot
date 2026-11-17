package com.example.ragbot.service;

import com.example.ragbot.domain.AppUser;
import com.example.ragbot.repository.AiModelRepository;
import com.example.ragbot.repository.PasswordResetRepository;
import com.example.ragbot.repository.UserRepository;
import com.example.ragbot.security.JwtService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordResetRepository resets;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final MailService mailService;
    private final AiModelRepository modelRepo;

    @Value("${spring.ai.openai.base-url}") private String aiBaseUrl;
    @Value("${spring.ai.openai.api-key}") private String aiApiKey;
    @Value("${spring.ai.openai.embedding.options.model}") private String aiEmbModel;
    @Value("${spring.ai.openai.embedding.options.dimensions:1024}") private int aiDim;
    @Value("${spring.ai.openai.chat.options.model}") private String aiChatModel;

    @Value("${security.default-admin.username:admin}") private String adminUsername;
    @Value("${security.default-admin.password:admin}") private String adminPassword;
    @Value("${security.default-admin.email:admin@ragbot.local}") private String adminEmail;
    @Value("${security.reset-password-url}") private String resetUrl;

    public AuthService(UserRepository users, PasswordResetRepository resets, PasswordEncoder encoder,
                       JwtService jwt, MailService mailService, AiModelRepository modelRepo) {
        this.users = users;
        this.resets = resets;
        this.encoder = encoder;
        this.jwt = jwt;
        this.mailService = mailService;
        this.modelRepo = modelRepo;
    }

    /** 首次启动创建默认管理员（强制首次改密），并把配置文件默认模型写入其名下 */
    @PostConstruct
    public void initAdmin() {
        UUID adminId;
        var existing = users.findByUsername(adminUsername);
        if (existing.isEmpty()) {
            adminId = users.insert(adminUsername, adminEmail, encoder.encode(adminPassword), "ADMIN", true);
        } else {
            adminId = existing.get().id();
        }
        // 首次初始化模型（若 admin 一个模型都没有）：用 application.yml 的默认值入库
        if (modelRepo.countByUser(adminId) == 0) {
            modelRepo.insert(adminId, "默认生成模型", "CHAT", aiBaseUrl, aiApiKey, aiChatModel, null, true);
            modelRepo.insert(adminId, "默认向量模型", "EMBEDDING", aiBaseUrl, aiApiKey, aiEmbModel, aiDim, true);
        }
    }

    public record LoginResult(String token, AppUser user, boolean mustChangePassword) {}

    public LoginResult login(String username, String password) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!user.enabled()) throw new IllegalArgumentException("账号已被禁用");
        if (!encoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String token = jwt.generate(user.id(), user.username(), user.role());
        return new LoginResult(token, user, user.mustChangePassword());
    }

    public LoginResult register(String username, String email, String password) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("用户名不能为空");
        if (email == null || email.isBlank() || !email.contains("@")) throw new IllegalArgumentException("邮箱格式不正确");
        if (password == null || password.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
        if (users.existsByUsername(username)) throw new IllegalArgumentException("用户名已存在");
        if (users.existsByEmail(email)) throw new IllegalArgumentException("邮箱已被注册");
        UUID id = users.insert(username, email, encoder.encode(password), "USER", false);
        AppUser user = users.findById(id).orElseThrow();
        String token = jwt.generate(user.id(), user.username(), user.role());
        return new LoginResult(token, user, false);
    }

    /** 修改密码；admin 首次改密会清除 must_change_password 标记 */
    public void changePassword(UUID userId, String oldPassword, String newPassword) {
        AppUser user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        // 管理员首次登录强制改密时，允许不校验旧密码是否为 admin（前端会传原密码）
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码至少 6 位");
        }
        if (user.mustChangePassword() && !user.isAdmin()) {
            // 普通用户被强制改密场景（预留）
        } else if (!encoder.matches(oldPassword, user.passwordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        users.updatePassword(userId, encoder.encode(newPassword));
    }

    /** 忘记密码：生成 token 并发送邮件（开发模式打印日志） */
    public void forgotPassword(String email) {
        AppUser user = users.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("该邮箱未注册"));
        String rawToken = generateToken();
        resets.create(rawToken, user.id(), 30);
        String link = resetUrl + "?token=" + rawToken;
        mailService.sendPasswordReset(email, link);
    }

    /** 重置密码：校验 token 后设置新密码 */
    public void resetPassword(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码至少 6 位");
        }
        UUID userId = resets.findValidUser(token)
                .orElseThrow(() -> new IllegalArgumentException("重置链接无效或已过期"));
        users.updatePassword(userId, encoder.encode(newPassword));
        resets.markUsed(token);
    }

    public AppUser getProfile(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
