package com.example.ragbot.service;

import com.example.ragbot.config.RagProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * MinIO（S3 兼容）对象存储实现（rag.storage.type=minio）。
 * 对象 key 与本地相对路径保持一致（日期/文件名），数据库无需改动。
 */
@Service
@ConditionalOnProperty(name = "rag.storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final RagProperties.Storage config;
    private final MinioClient client;

    public MinioStorageService(RagProperties properties) {
        this.config = properties.getStorage();
        this.client = MinioClient.builder()
                .endpoint(config.getEndpoint())
                .credentials(config.getAccessKey(), config.getSecretKey())
                .build();
    }

    @PostConstruct
    public void init() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(config.getBucket()).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(config.getBucket()).build());
            log.info("MinIO bucket created: {}", config.getBucket());
        }
        log.info("MinIO storage ready: endpoint={}, bucket={}", config.getEndpoint(), config.getBucket());
    }

    @Override
    public String save(MultipartFile file, String storedFilename) throws IOException {
        String objectName = LocalDate.now() + "/" + storedFilename;
        try (InputStream in = file.getInputStream()) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(config.getBucket())
                    .object(objectName)
                    .stream(in, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new IOException("Failed to upload object to MinIO: " + objectName, e);
        }
        return objectName;
    }

    /** 从 MinIO 下载到临时文件，调用方按普通本地文件读取即可；JVM 退出时自动清理 */
    @Override
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Empty storage path");
        }
        try {
            String ext = getExtension(relativePath);
            Path tmp = Files.createTempFile("rag-minio-", ext);
            tmp.toFile().deleteOnExit();
            try (InputStream in = client.getObject(GetObjectArgs.builder()
                    .bucket(config.getBucket())
                    .object(relativePath)
                    .build())) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (Exception e) {
            throw new RuntimeException("Failed to download object from MinIO: " + relativePath, e);
        }
    }

    @Override
    public void delete(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) return;
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(config.getBucket())
                    .object(relativePath)
                    .build());
        } catch (Exception e) {
            throw new IOException("Failed to delete object from MinIO: " + relativePath, e);
        }
    }

    private static String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot) : "";
    }
}
