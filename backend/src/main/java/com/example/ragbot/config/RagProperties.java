package com.example.ragbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private final Storage storage = new Storage();
    private final Chunk chunk = new Chunk();
    private final Retrieval retrieval = new Retrieval();
    private final Cache cache = new Cache();
    private final Prompt prompt = new Prompt();

    public Storage getStorage() { return storage; }
    public Chunk getChunk() { return chunk; }
    public Retrieval getRetrieval() { return retrieval; }
    public Cache getCache() { return cache; }
    public Prompt getPrompt() { return prompt; }

    public static class Storage {
        /** 存储类型：local（本地磁盘，默认）或 minio（S3 兼容对象存储） */
        private String type = "local";
        /** 本地存储根目录 */
        private String path = "./storage";

        // ---- MinIO / S3 配置 ----
        private String endpoint = "http://localhost:9000";
        private String bucket = "rag-bot";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }

    public static class Chunk {
        private String strategy = "SEMANTIC";
        private int size = 800;
        private int overlap = 120;
        private int minSize = 120;
        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public int getOverlap() { return overlap; }
        public void setOverlap(int overlap) { this.overlap = overlap; }
        public int getMinSize() { return minSize; }
        public void setMinSize(int minSize) { this.minSize = minSize; }
    }

    public static class Retrieval {
        private int topK = 8;
        private int candidateK = 30;
        private double similarityThreshold = 0.25;
        private String fusion = "RRF";
        private int rrfK = 60;
        private double vectorWeight = 0.65;
        private double bm25Weight = 0.35;
        private final Rerank rerank = new Rerank();

        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public int getCandidateK() { return candidateK; }
        public void setCandidateK(int candidateK) { this.candidateK = candidateK; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public String getFusion() { return fusion; }
        public void setFusion(String fusion) { this.fusion = fusion; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public double getVectorWeight() { return vectorWeight; }
        public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }
        public double getBm25Weight() { return bm25Weight; }
        public void setBm25Weight(double bm25Weight) { this.bm25Weight = bm25Weight; }
        public Rerank getRerank() { return rerank; }
    }

    public static class Rerank {
        private boolean enabled = false;
        private String model = "bge-reranker-v2-m3";
        private String baseUrl = "https://api.openai.com";
        private String apiKey = "sk-change-me";
        private int topK = 8;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

    public static class Cache {
        private boolean enabled = false;
        private double similarityThreshold = 0.97;
        private int ttlHours = 24;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    }

    public static class Prompt {
        private String system = "你是一个严谨的知识库助手，只根据提供的上下文回答。";
        private String generalSystem = "你是一个友好、专业的 AI 助手。请直接、准确地回答用户问题。";
        public String getSystem() { return system; }
        public void setSystem(String system) { this.system = system; }
        public String getGeneralSystem() { return generalSystem; }
        public void setGeneralSystem(String generalSystem) { this.generalSystem = generalSystem; }
    }
}
