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
 */
@Slf4j
public class UserKnowledgeMemoryStoreImpl implements UserKnowledgeMemoryStore {

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
