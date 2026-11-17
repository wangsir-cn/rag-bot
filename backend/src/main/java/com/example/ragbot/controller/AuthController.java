package com.example.ragbot.controller;

import com.example.ragbot.domain.AppUser;
import com.example.ragbot.dto.ErrorResponse;
import com.example.ragbot.security.CurrentUser;
import com.example.ragbot.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    public record LoginRequest(String username, String password) {}
    public record RegisterRequest(String username, String email, String password) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
    public record ForgotRequest(String email) {}
    public record ResetRequest(String token, String newPassword) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        var result = authService.login(req.username(), req.password());
        return Map.of(
                "token", result.token(),
                "mustChangePassword", result.mustChangePassword(),
                "user", userView(result.user())
        );
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest req) {
        var result = authService.register(req.username(), req.email(), req.password());
        return Map.of("token", result.token(), "user", userView(result.user()));
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest req) {
        authService.changePassword(currentUser.id(), req.oldPassword(), req.newPassword());
        return Map.of("success", true);
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgot(@RequestBody ForgotRequest req) {
        authService.forgotPassword(req.email());
        return Map.of("success", true, "message", "重置链接已发送，请查收邮箱");
    }

    @PostMapping("/reset-password")
    public Map<String, Object> reset(@RequestBody ResetRequest req) {
        authService.resetPassword(req.token(), req.newPassword());
        return Map.of("success", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return userView(currentUser.get());
    }

    private Map<String, Object> userView(AppUser u) {
        return Map.of(
                "id", u.id().toString(),
                "username", u.username(),
                "email", u.email(),
                "role", u.role(),
                "mustChangePassword", u.mustChangePassword()
        );
    }
}
