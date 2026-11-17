package com.example.ragbot.service;

import com.example.ragbot.config.RagProperties;
import com.example.ragbot.domain.Chunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 阶段 2：可配置分块策略。
 * - FIXED   ：固定字符长度 + overlap，边界尽量落在标点/空白上。
 * - SEMANTIC：先按段落、再按句子切分，聚合到目标长度，尾部过短则合并，块间带 overlap。
 */
@Service
public class ChunkingService {

    private static final Pattern PARA_SPLIT = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern SENT_SPLIT = Pattern.compile("[。！？!?；;\\n]+");
    private static final String BOUNDARY_CHARS = "。！？；，,!?;:： \t\n";

    private final RagProperties properties;

    public ChunkingService(RagProperties properties) {
        this.properties = properties;
    }

    public List<Chunk> chunk(UUID documentId, String text, Map<String, Object> baseMetadata) {
        return chunk(documentId, text, baseMetadata,
                properties.getChunk().getStrategy(),
                properties.getChunk().getSize(),
                properties.getChunk().getOverlap());
    }

    /** 按知识库自定义分块参数切分（v2：知识库可配 strategy/size/overlap） */
    public List<Chunk> chunk(UUID documentId, String text, Map<String, Object> baseMetadata,
                             String strategy, int sizeCfg, int overlapCfg) {
        int size = Math.max(100, sizeCfg);
        int overlap = Math.max(0, Math.min(overlapCfg, size / 2));
        int minSize = Math.max(0, properties.getChunk().getMinSize());

        List<String> parts = "SEMANTIC".equalsIgnoreCase(strategy)
                ? semanticSplit(text, size, overlap, minSize)
                : fixedSplit(text, size, overlap);

        List<Chunk> chunks = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String content = parts.get(i);
            if (content.isBlank()) continue;
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("chunkIndex", i);
            metadata.put("charLength", content.length());
            chunks.add(new Chunk(documentId, i, content, Map.copyOf(metadata)));
        }
        return chunks;
    }

    private List<String> fixedSplit(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                int floor = start + (int) (size * 0.8);
                int boundary = findBoundary(text, floor, end);
                if (boundary > start) end = boundary;
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) result.add(piece);
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return result;
    }

    private List<String> semanticSplit(String text, int size, int overlap, int minSize) {
        List<String> units = new ArrayList<>();
        for (String paragraph : PARA_SPLIT.split(text)) {
            String p = paragraph.trim();
            if (p.isEmpty()) continue;
            if (p.length() <= size) {
                units.add(p);
            } else {
                for (String sentence : SENT_SPLIT.split(p)) {
                    String s = sentence.trim();
                    if (!s.isEmpty()) units.add(s);
                }
            }
        }

        List<String> normalized = new ArrayList<>();
        for (String unit : units) {
            if (unit.length() <= size) {
                normalized.add(unit);
            } else {
                int start = 0;
                while (start < unit.length()) {
                    int end = Math.min(start + size, unit.length());
                    normalized.add(unit.substring(start, end));
                    start = end;
                }
            }
        }

        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : normalized) {
            int extra = current.length() == 0 ? 0 : 1;
            if (current.length() + extra + unit.length() <= size) {
                if (current.length() > 0) current.append('\n');
                current.append(unit);
            } else {
                if (current.length() > 0) packed.add(current.toString());
                current.setLength(0);
                current.append(unit);
            }
        }
        if (current.length() > 0) packed.add(current.toString());

        // 尾部碎片并入上一块，避免出现只有十几个字的无效 chunk
        if (packed.size() >= 2 && packed.get(packed.size() - 1).length() < minSize) {
            String tail = packed.remove(packed.size() - 1);
            int last = packed.size() - 1;
            packed.set(last, packed.get(last) + "\n" + tail);
        }

        // 为相邻块注入 overlap，缓解答案跨越切分边界时被截断的问题
        List<String> result = new ArrayList<>();
        for (int i = 0; i < packed.size(); i++) {
            String content = packed.get(i);
            if (i > 0 && overlap > 0) {
                String previous = packed.get(i - 1);
                String tail = previous.length() <= overlap
                        ? previous
                        : previous.substring(previous.length() - overlap);
                int cut = findBoundary(tail, 0, tail.length());
                if (cut > 0 && cut < tail.length()) tail = tail.substring(cut);
                content = tail.trim() + "\n" + content;
            }
            result.add(content);
        }
        return result;
    }

    private int findBoundary(String text, int from, int to) {
        for (int i = to - 1; i >= from && i >= 0; i--) {
            if (BOUNDARY_CHARS.indexOf(text.charAt(i)) >= 0) return i + 1;
        }
        return -1;
    }
}
