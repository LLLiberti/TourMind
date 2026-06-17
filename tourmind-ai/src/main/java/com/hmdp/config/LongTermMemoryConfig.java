package com.hmdp.config;

import com.hmdp.memory.longterm.UserKnowledgeMemoryStore;
import com.hmdp.memory.longterm.Impl.UserKnowledgeMemoryStoreImpl;
import com.hmdp.memory.longterm.MemoryExtractor;
import com.hmdp.memory.longterm.Impl.MemoryExtractorImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 记忆模块配置 — 长期记忆组件 Bean 装配。
 *
 * <h3>组件清单</h3>
 * <ul>
 *   <li>{@link UserKnowledgeMemoryStore} — Qdrant 向量存储（非结构化知识/事件）</li>
 *   <li>{@link MemoryExtractor} — LLM 记忆提取器</li>
 *   <li>{@code @EnableAsync} — 启用异步记忆写入</li>
 * </ul>
 *
 * <h3>Qdrant Collection</h3>
 * <p>KnowledgeMemoryStore 使用独立的 Qdrant collection ({@code user_memories})，
 * 与主知识库 ({@code spot_knowledge_base}) 隔离。</p>
 */
@Slf4j
@Configuration
@EnableAsync
public class LongTermMemoryConfig {

    /**
     * 用户记忆 Qdrant VectorStore — 独立 collection，与主知识库隔离。
     *
     * <p>通过条件装配支持降级：无 Qdrant 时 Bean 不创建，
     * {@code KnowledgeMemoryStore} 也不会创建。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "memory.longterm.qdrant", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public UserKnowledgeMemoryStore knowledgeMemoryStore(
            @Qualifier("userMemoriesVectorStore") VectorStore userMemoriesVectorStore) {
        log.info("KnowledgeMemoryStore 初始化: Qdrant collection=user_memories");
        return new UserKnowledgeMemoryStoreImpl(userMemoriesVectorStore);
    }

    /**
     * 记忆提取器 — 使用 DeepSeek 进行对话记忆提取。
     */
    @Bean
    @ConditionalOnProperty(prefix = "memory.extractor", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MemoryExtractor memoryExtractor(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        log.info("MemoryExtractor 初始化: model=deepseek-chat");
        return new MemoryExtractorImpl(chatModel);
    }
}
