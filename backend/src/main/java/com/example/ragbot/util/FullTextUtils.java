package com.example.ragbot.util;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 为 PostgreSQL tsvector('simple', ...) 生成友好分词文本。
 *
 * 标准 pgvector 镜像不带中文分词扩展（zhparser），'simple' 配置会把整段中文当成一个 token，
 * 导致 BM25 检索中文几乎失效。这里用一个轻量方案：中文按「二元切分(bigram)」展开，
 * 英文/数字保留原词。写入 chunks.search_text 与查询时都调用本方法，保证索引与查询一致。
 */
public final class FullTextUtils {

    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff]+");
    private static final Pattern CJK_PUNCT = Pattern.compile("[\\u3000-\\u303f\\uff00-\\uffef]");
    private static final Pattern NOISE = Pattern.compile("[\\p{Cntrl}\\p{Punct}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private FullTextUtils() {}

    public static String buildSearchText(String text) {
        if (text == null || text.isBlank()) return "";
        // 中文标点先替换为空格，避免污染 token
        String cleaned = CJK_PUNCT.matcher(text).replaceAll(" ");
        Matcher matcher = CJK_RUN.matcher(cleaned);
        StringBuilder sb = new StringBuilder(cleaned.length() * 2);
        int last = 0;
        while (matcher.find()) {
            sb.append(normalize(cleaned.substring(last, matcher.start()))).append(' ');
            String run = matcher.group();
            if (run.length() == 1) {
                sb.append(run).append(' ');
            } else {
                for (int i = 0; i < run.length() - 1; i++) {
                    sb.append(run, i, i + 2).append(' ');
                }
            }
            last = matcher.end();
        }
        sb.append(normalize(cleaned.substring(last)));
        return sb.toString().trim();
    }

    /**
     * 构造 OR 语义的 to_tsquery 表达式：'词1' | '词2' | ...
     * plainto_tsquery 默认把多个词用 AND 连接，长查询几乎不可能全部命中，
     * 会严重压低 BM25 召回；OR 语义更符合"任一相关词出现即候选"的召回目标。
     */
    public static String buildTsQuery(String text) {
        String searchText = buildSearchText(text);
        if (searchText.isBlank()) return "";
        return Arrays.stream(searchText.split("\\s+"))
                .filter(token -> !token.isBlank())
                .distinct()
                .map(token -> "'" + token.replace("'", "''") + "'")
                .collect(Collectors.joining(" | "));
    }

    private static String normalize(String s) {
        return SPACES.matcher(NOISE.matcher(s).replaceAll(" ")).replaceAll(" ").trim();
    }
}
