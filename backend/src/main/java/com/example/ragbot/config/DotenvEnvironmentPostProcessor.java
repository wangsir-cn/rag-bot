package com.example.ragbot.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让 Spring Boot 在本地启动时也能读取项目根目录的 .env 文件。
 *
 * <p>背景：.env 是 Docker Compose 的约定，Spring Boot 本身并不认识它。
 * 这个处理器在 Environment 准备阶段把 .env 中的 {@code KEY=VALUE} 解析成一个
 * PropertySource 注入，从而让 application.yml 里的 {@code ${DB_HOST:...}} 等占位符
 * 能拿到值。</p>
 *
 * <p>优先级：命令行参数 / 系统属性 / 真实环境变量 &gt; .env &gt; application.yml 默认值。
 * 真实环境变量优先，这样 Docker Compose 通过 environment 注入的值不会被 .env 覆盖。</p>
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String PROPERTY_SOURCE_NAME = "dotenv";
    private static final String DOTENV_FILE = ".env";
    private static final int MAX_PARENT_LEVELS = 4;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenv = locateDotenv();
        if (dotenv == null) {
            return;
        }

        Map<String, Object> values = parse(dotenv);
        if (values.isEmpty()) {
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, values);
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
    }

    /** 从当前工作目录逐级向上查找 .env（覆盖在 backend/ 或项目根启动两种情况）。 */
    private Path locateDotenv() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i <= MAX_PARENT_LEVELS && dir != null; i++) {
            Path candidate = dir.resolve(DOTENV_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** 解析 KEY=VALUE：忽略空行与 # 注释，支持 export 前缀与成对引号。 */
    private Map<String, Object> parse(Path file) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring("export ".length()).trim();
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                value = unquote(value);
                if (!key.isEmpty()) {
                    result.put(key, value);
                }
            }
        } catch (IOException e) {
            // 读不到 .env 时静默跳过，交由真实环境变量或默认值处理
        }
        return result;
    }

    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
