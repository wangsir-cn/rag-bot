package com.example.ragbot.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文件存储抽象。本地磁盘与 S3 兼容对象存储（MinIO）各有一个实现，
 * 通过 rag.storage.type 切换，上层 DocumentService / IngestionWorker 无需改动。
 *
 * > [!NOTE] 相对路径约定
 * 三个方法都以「相对根目录的路径」为标识（如 2026-11-17/xxx.docx）。
 * 本地实现相对 storage 根目录，MinIO 实现相对 bucket。数据库 documents.file_path 存这个相对路径，
 * 因此从本地切到 MinIO 只要用相同相对路径上传对象，已有数据记录无需修改。
 */
public interface StorageService {

    /** 保存文件，返回相对路径 */
    String save(MultipartFile file, String storedFilename) throws IOException;

    /**
     * 解析相对路径为可读文件。
     * 本地实现返回实际文件路径；MinIO 实现下载到临时文件后返回其路径。
     * 调用方只读取内容，不修改文件。
     */
    Path resolve(String relativePath);

    /** 删除文件 */
    void delete(String relativePath) throws IOException;
}
