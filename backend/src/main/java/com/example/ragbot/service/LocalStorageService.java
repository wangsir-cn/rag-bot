package com.example.ragbot.service;

import com.example.ragbot.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** 本地文件系统存储实现（rag.storage.type=local，默认） */
@Service
@ConditionalOnProperty(name = "rag.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(RagProperties properties) {
        this.root = Path.of(properties.getStorage().getPath()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root);
    }

    /** 返回相对根目录的路径，按日期分目录避免单目录文件过多 */
    @Override
    public String save(MultipartFile file, String storedFilename) throws IOException {
        Path dir = root.resolve(LocalDate.now().toString()).normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(storedFilename).normalize();
        file.transferTo(target.toFile());
        return root.relativize(target).toString().replace('\\', '/');
    }

    /** 校验相对路径，防止路径穿越 */
    @Override
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Illegal storage path: " + relativePath);
        }
        return resolved;
    }

    @Override
    public void delete(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) return;
        Files.deleteIfExists(resolve(relativePath));
    }
}
