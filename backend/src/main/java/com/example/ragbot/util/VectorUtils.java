package com.example.ragbot.util;

/** pgvector 文本表示 [:float] 与 Java float[] 之间的转换，避免引入额外 JDBC 类型依赖。 */
public final class VectorUtils {

    private VectorUtils() {}

    /** float[] -> "[0.123,0.456,...]"，可直接用于 SQL 中的 ?::vector */
    public static String toPgLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("embedding vector must not be empty");
        }
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            float v = vector[i];
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new IllegalArgumentException("embedding contains NaN/Inf at index " + i);
            }
            sb.append(Float.toString(v));
        }
        return sb.append(']').toString();
    }

    /** "[0.123,0.456]" -> float[] */
    public static float[] fromPgLiteral(String literal) {
        if (literal == null || literal.isBlank()) {
            return new float[0];
        }
        String s = literal.trim();
        if (s.length() >= 2 && s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isBlank()) return new float[0];
        String[] parts = s.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    /** 余弦相似度，用于语义缓存命中判断 */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0f || nb == 0f) return 0f;
        return dot / (float) Math.sqrt(na * nb);
    }
}
