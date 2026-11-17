package com.example.ragbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** 密码重置邮件；未配置 SMTP 时降级为日志打印（开发模式） */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String username;
    private final String from;

    public MailService(JavaMailSender mailSender,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${app.mail.from:}") String from) {
        this.mailSender = mailSender;
        this.username = username;
        this.from = from;
    }

    public void sendPasswordReset(String toEmail, String resetUrl) {
        if (username == null || username.isBlank()) {
            // 开发模式：不真正发信，打印链接便于测试
            log.warn("=== 密码重置链接（未配置 SMTP，仅日志输出）===\n收件人: {}\n链接: {}", toEmail, resetUrl);
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from.isBlank() ? username : from);
        msg.setTo(toEmail);
        msg.setSubject("RAG Bot 密码重置");
        msg.setText("您好，\n\n请点击以下链接重置密码（30 分钟内有效）：\n" + resetUrl
                + "\n\n若非本人操作请忽略此邮件。");
        mailSender.send(msg);
    }
}
