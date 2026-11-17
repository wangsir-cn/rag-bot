package com.example.ragbot.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** QA 文件解析：问题 + 答案两列，支持 CSV / TSV / TXT(制表符或逗号分隔) */
@Service
public class QaParsingService {

    public record QaRow(String question, String answer) {}

    /** 返回解析出的问答对，第一行表头会被自动跳过 */
    public List<QaRow> parse(Path file) throws IOException {
        List<QaRow> rows = new ArrayList<>();
        // 尝试 UTF-8，带 BOM 处理
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    if (line.isBlank()) continue;
                    // 表头检测：包含"问题/question"与"答案/answer"字样则跳过
                    String lower = line.toLowerCase();
                    if (lower.contains("问题") || lower.contains("question")) continue;
                }
                if (line.isBlank()) continue;
                String[] parts = splitLine(line);
                if (parts.length < 2) continue;
                String question = parts[0].trim();
                String answer = parts[1].trim();
                if (question.isEmpty() || answer.isEmpty()) continue;
                rows.add(new QaRow(question, answer));
            }
        }
        return rows;
    }

    /** 支持 CSV（逗号，含引号）与 TSV（制表符） */
    private String[] splitLine(String line) {
        if (line.contains("\t")) return line.split("\t", -1);
        return parseCsv(line);
    }

    private String[] parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }

    /** 生成 QA 模板内容（供前端下载） */
    public String template() {
        return "问题,答案\n" +
               "示例：产品的核心功能是什么？,示例：本产品提供知识库问答与文档检索能力。\n" +
               "示例：如何重置密码？,示例：在登录页点击忘记密码，通过注册邮箱接收重置链接。\n";
    }
}
