package com.example.ragbot.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DotenvEnvironmentPostProcessorTest {

    @Test
    void loadsDotenvFromProjectRoot() throws Exception {
        // 测试从 backend 目录运行，.env 应能在上一级项目根目录被找到
        Path cwd = Paths.get("").toAbsolutePath();
        Path envFile = cwd.getParent().resolve(".env");
        assertTrue(Files.isRegularFile(envFile), "项目根 .env 不存在: " + envFile);

        ConfigurableEnvironment environment = new StandardEnvironment();
        new DotenvEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        String host = environment.getProperty("DB_HOST");
        String name = environment.getProperty("DB_NAME");
        assertNotNull(host, "DB_HOST 未被注入");
        assertFalse(host.isBlank(), "DB_HOST 不能为空");
        assertNotNull(name, "DB_NAME 未被注入");
        assertFalse(name.isBlank(), "DB_NAME 不能为空");
        assertNotNull(environment.getPropertySources().get("dotenv"));
    }

    @Test
    void resolvesDatasourceUrlPlaceholder() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        new DotenvEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        String url = environment.resolvePlaceholders("jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:ragbot}");
        assertNotNull(url, "URL 解析结果不能为空");
        assertFalse(url.contains("${"), "URL 中仍存在未解析的占位符: " + url);
        assertTrue(url.startsWith("jdbc:postgresql://"), "URL 前缀不合法: " + url);
    }
}
