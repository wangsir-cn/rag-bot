package com.example.ragbot.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * QA 文件解析：两列（问题、答案）。
 * 支持 Excel（.xlsx/.xls，取第一个工作表的 A/B 两列）与纯文本（CSV/TSV/TXT）。
 * 文本会做编码嗅探：BOM → UTF-16 → UTF-8 → GB18030，避免 Excel 另存的 GBK CSV 解码失败。
 */
@Service
public class QaParsingService {

    private static final Logger log = LoggerFactory.getLogger(QaParsingService.class);
    /** GB18030 是 GBK/GB2312 的超集，用于兼容 Excel 导出的中文 CSV。 */
    private static final Charset GB18030 = Charset.forName("GB18030");

    public record QaRow(String question, String answer) {}

    /** 返回解析出的问答对，第一行表头会被自动跳过 */
    public List<QaRow> parse(Path file) throws IOException {
        byte[] head = readHead(file, 8);
        if (isZip(head)) {
            return parseExcel(file, true);
        }
        if (isOle2(head)) {
            return parseExcel(file, false);
        }
        if (isPdf(head)) {
            throw new IllegalArgumentException("QA 文件不支持 PDF，请提供 Excel（.xlsx/.xls）或两列文本（.csv/.tsv/.txt）");
        }
        return parseText(file);
    }

    // ---------------------------------------------------------------- Excel

    /** xlsx 是 zip 容器，xls 是 OLE2 容器；两者都用 POI 读取第一个工作表的前两列 */
    private List<QaRow> parseExcel(Path file, boolean zipBased) throws IOException {
        Workbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = zipBased ? new XSSFWorkbook(in) : new HSSFWorkbook(in);
        } catch (Exception e) {
            throw new IllegalArgumentException(describeContainer(file, zipBased), e);
        }
        List<QaRow> rows = new ArrayList<>();
        try (Workbook wb = workbook) {
            Sheet sheet = wb.getNumberOfSheets() == 0 ? null : wb.getSheetAt(0);
            if (sheet == null) throw new IllegalArgumentException("Excel 中没有工作表，无法解析 QA");
            DataFormatter formatter = new DataFormatter();
            boolean first = true;
            for (Row row : sheet) {
                String question = cellText(row, 0, formatter);
                String answer = cellText(row, 1, formatter);
                if (question.isEmpty() && answer.isEmpty()) continue;
                if (first) {
                    first = false;
                    if (isHeader(question, answer)) continue;
                }
                if (question.isEmpty() || answer.isEmpty()) continue;
                rows.add(new QaRow(question, answer));
            }
        }
        return rows;
    }

    private String cellText(Row row, int index, DataFormatter formatter) {
        if (row == null) return "";
        Cell cell = row.getCell(index);
        if (cell == null) return "";
        return formatter.formatCellValue(cell).strip();
    }

    /** 容器打不开时给出可执行的提示，而不是把 POI 的底层异常直接抛给用户 */
    private String describeContainer(Path file, boolean zipBased) {
        if (zipBased) {
            if (zipContains(file, "word/document.xml")) {
                return "该文件是 Word 文档（.docx），QA 上传请使用 Excel（.xlsx/.xls）或两列文本（.csv/.tsv/.txt）";
            }
            if (!zipContains(file, "xl/workbook.xml")) {
                return "该文件是普通压缩包，不含 Excel 工作表；QA 上传请使用 .xlsx/.xls 或两列文本";
            }
            return "Excel 文件已损坏或格式不受支持，请用 Excel 打开后另存为 .xlsx 再上传";
        }
        if (zipContains(file, "word/document.xml")) {
            return "该文件是 Word 文档（.doc），QA 上传请使用 Excel 或两列文本";
        }
        return "Excel 文件已损坏或格式不受支持，请用 Excel 打开后另存为 .xlsx 再上传";
    }

    private boolean zipContains(Path file, String entry) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.getEntry(entry) != null;
        } catch (Exception ignore) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 纯文本

    private List<QaRow> parseText(Path file) throws IOException {
        Charset detected = detectCharset(file);
        List<Charset> candidates = new ArrayList<>();
        candidates.add(detected);
        for (Charset c : new Charset[]{GB18030, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
            if (!candidates.contains(c)) candidates.add(c);
        }
        CharacterCodingException last = null;
        for (Charset candidate : candidates) {
            try {
                return parse(file, candidate);
            } catch (CharacterCodingException e) {
                last = e;
                log.warn("QA 文件按 {} 解码失败，尝试其他编码: {}", candidate.name(), file.getFileName());
            }
        }
        // 所有编码都不严格合法：以 GB18030 替换非法字节，保证文件不会被整份丢弃
        log.warn("QA 文件编码无法确定，改用 GB18030 + 替换模式解析，头部字节={}，文件={}",
                hex(headBytes(file, 16)), file.getFileName());
        if (last == null) throw new IOException("QA 文件编码解析失败");
        return parseLenient(file, GB18030);
    }

    private List<QaRow> parse(Path file, Charset charset) throws IOException {
        List<QaRow> rows = new ArrayList<>();
        // 使用 REPORT 而不是默认的 REPLACE：编码不匹配时抛异常，才能触发上面的编码回退。
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStream in = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, decoder))) {
            readRows(reader, rows);
        }
        return rows;
    }

    private List<QaRow> parseLenient(Path file, Charset charset) throws IOException {
        List<QaRow> rows = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
            readRows(reader, rows);
        }
        return rows;
    }

    private void readRows(BufferedReader reader, List<QaRow> rows) throws IOException {
        String line;
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            line = stripBom(line);
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

    /** 优先按 BOM 判断 UTF-8/UTF-16；无 BOM 时按零字节分布判断 UTF-16，否则先按 UTF-8 尝试。 */
    private Charset detectCharset(Path file) throws IOException {
        byte[] head = readHead(file, 4096);
        int n = head.length;
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (n >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        // 无 BOM 的 UTF-16：ASCII 字符间隔出现 0x00，按奇偶位置的零字节比例判断
        int evenZero = 0, oddZero = 0, pairs = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            if (head[i] == 0) evenZero++;
            if (head[i + 1] == 0) oddZero++;
        }
        if (pairs > 4) {
            if (evenZero > pairs * 0.3 && oddZero < pairs * 0.1) return StandardCharsets.UTF_16LE;
            if (oddZero > pairs * 0.3 && evenZero < pairs * 0.1) return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_8;
    }

    private boolean isHeader(String question, String answer) {
        String q = question.toLowerCase();
        String a = answer.toLowerCase();
        return q.contains("问题") || q.contains("question") || a.contains("答案") || a.contains("answer");
    }

    private byte[] readHead(Path file, int max) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(max);
        }
    }

    private byte[] headBytes(Path file, int max) {
        try {
            return readHead(file, max);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private boolean isZip(byte[] h) {
        return h.length >= 4 && (h[0] & 0xFF) == 0x50 && (h[1] & 0xFF) == 0x4B
                && ((h[2] & 0xFF) == 0x03 || (h[2] & 0xFF) == 0x05 || (h[2] & 0xFF) == 0x07);
    }

    private boolean isOle2(byte[] h) {
        return h.length >= 8 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF
                && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0;
    }

    private boolean isPdf(byte[] h) {
        return h.length >= 4 && h[0] == 0x25 && h[1] == 0x50 && h[2] == 0x44 && h[3] == 0x46;
    }

    /** UTF-8/UTF-16 BOM 会被解码成首个 U+FEFF，去掉以免污染第一列表头或问题。 */
    private String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
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
