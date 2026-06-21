package com.hmdp.memory.longterm.Impl;

import com.hmdp.entity.memory.MemoryEntry;
import com.hmdp.memory.longterm.UserKnowledgeMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识记忆存储 Qdrant 实现 — 复用 Spring AI VectorStore。
 *
 * <p>Collection: user_memories（需在 Qdrant 中预先创建或通过 initializeSchema=true 自动创建）。
 * Embedding: Ollama bge-m3（复用现有配置）。</p>
 *
 * <h3>去重策略</h3>
 * <p>写入前对同 userId 做语义相似检索，若已有高度相似记忆（score > 0.85），
 * 跳过写入避免 Qdrant 中堆积重复内容。</p>
 */
@Slf4j
public class UserKnowledgeMemoryStoreImpl implements UserKnowledgeMemoryStore {

    /** 去重相似度阈值：新记忆与已有记忆相似度 >= 此值时跳过写入 */
    private static final double DEDUP_THRESHOLD = 0.85;

    /**
     * 名为 "userMemoriesVectorStore" 的 Qdrant VectorStore，
     * 指向独立的 collection（区别于主知识库的 spot_knowledge_base）。
     */
    private final VectorStore vectorStore;

    public UserKnowledgeMemoryStoreImpl(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public String store(Long userId, String content, String memoryType, int importance) {
        // 去重：搜索同 userId 下的相似记忆
        if (isDuplicate(userId, content)) {
            log.debug("KnowledgeMemory: 跳过重复记忆, userId={}, content='{}'",
                    userId, content.length() > 40 ? content.substring(0, 40) + "..." : content);
            return null;
        }

        String docId = UUID.randomUUID().toString();
        Map<String, Object> metadata = Map.of(
                "userId", userId,
                "memoryType", memoryType,
                "importance", importance,
                "timestamp", System.currentTimeMillis() / 1000
        );
        Document doc = new Document(docId, content, metadata);
        vectorStore.add(List.of(doc));
        log.debug("KnowledgeMemory 存储: userId={}, type={}, importance={}", userId, memoryType, importance);
        return docId;
    }

    /**
     * 检查是否已存在高度相似记忆。
     */
    private boolean isDuplicate(Long userId, String content) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(content)
                    .topK(1)
                    .similarityThreshold(DEDUP_THRESHOLD)
                    .filterExpression(new FilterExpressionBuilder()
                            .eq("userId", userId)
                            .build())
                    .build();
            List<Document> docs = vectorStore.similaritySearch(request);
            return !docs.isEmpty();
        } catch (Exception e) {
            // 去重检查失败不阻塞写入
            log.debug("KnowledgeMemory 去重检查失败，放行写入: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<MemoryEntry> search(Long userId, String query, int topK) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(0.3) // 记忆检索阈值略低于知识库检索
                    .filterExpression(new FilterExpressionBuilder()
                            .eq("userId", userId)
                            .build())
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);

            return docs.stream()
                    .map(this::toMemoryEntry)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("KnowledgeMemory 检索失败: userId={}, query={}, msg={}", userId, query, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void delete(String memoryId) {
        try {
            vectorStore.delete(List.of(memoryId));
        } catch (Exception e) {
            log.warn("KnowledgeMemory 删除失败: id={}, msg={}", memoryId, e.getMessage());
        }
    }

    private MemoryEntry toMemoryEntry(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        return MemoryEntry.builder()
                .id(doc.getId())
                .userId(getLong(meta, "userId"))
                .memoryType(getString(meta, "memoryType", "unknown"))
                .content(doc.getText())
                .importance(getInt(meta, "importance", 1))
                .timestamp(getLong(meta, "timestamp"))
                .build();
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private static long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
