package com.example.ragbot.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 阶段 2：用 Apache Tika 统一解析 PDF / Word / TXT / Markdown。
 * AutoDetectParser 会按 MIME 自动选择 PDFBox、POI 等解析器，业务代码无需关心文件类型。
 */
@Service
public class ParsingService {

    // parser 初始化会加载全部子解析器，必须复用单例，不能在每次请求中 new
    private final AutoDetectParser parser = new AutoDetectParser();

    public String parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            BodyContentHandler handler = new BodyContentHandler(-1); // -1 = 不限制输出长度
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
            parser.parse(in, handler, metadata, new ParseContext());
            return normalize(handler.toString());
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("文档解析失败: " + file.getFileName(), e);
        }
    }

    private String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u0000", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .trim();
    }
}
