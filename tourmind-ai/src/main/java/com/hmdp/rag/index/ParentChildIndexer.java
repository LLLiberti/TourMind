package com.hmdp.rag.index;

import com.hmdp.entity.Spot;
import com.hmdp.entity.SpotKnowledge;
import com.hmdp.mapper.SpotKnowledgeMapper;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.rag.chunking.SpotKnowledgeEnricher;
import com.hmdp.rag.chunking.SpotKnowledgeSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子文档索引协调器 — 将景点知识按 Parent-Child 策略索引到 Qdrant + BM25 + MySQL + Redis。
 *
 * <h3>索引结构</h3>
 * <ul>
 *   <li><b>父文档</b>（docType="parent"）：完整富化文本，存入 Qdrant</li>
 *   <li><b>子文档</b>（docType="child"）：按 [Section] 语义切分的 chunk，存入 Qdrant
 *       并加入 Elasticsearch BM25 关键词索引</li>
 *   <li><b>MySQL</b>：父文档全文持久化到 {@code tb_spot_knowledge} 表</li>
 *   <li><b>Redis</b>：父文档全文缓存，key={@code spot:knowledge:parent:{spotId}}</li>
 * </ul>
 *
 * <h3>父文档查询路径</h3>
 * <pre>
 * loadParentText(spotId):
 *   ① Redis "spot:knowledge:parent:{spotId}" → 命中返回
 *   ② MySQL tb_spot_knowledge WHERE spot_id=? → 命中回写Redis返回
 *   ③ SpotMapper.selectById + enricher.enrich → 写MySQL+Redis → 返回
 * </pre>
 */
@Slf4j
public class ParentChildIndexer {

    /** 父文档元数据标识 */
    public static final String DOC_TYPE_PARENT = "parent";

    /** 子文档元数据标识 */
    public static final String DOC_TYPE_CHILD = "child";

    /** 元数据键 */
    public static final String META_DOC_TYPE = "docType";
    public static final String META_SPOT_ID = "spotId";
    public static final String META_SPOT_NAME = "spotName";
    public static final String META_CHUNK_TOPIC = "chunkTopic";
    public static final String META_CHUNK_INDEX = "chunkIndex";

    /** Redis key 前缀 */
    private static final String REDIS_PARENT_KEY_PREFIX = "spot:knowledge:parent:";

    /** Qdrant 子文档过滤表达式（用于向量检索时仅查子文档） */
    public static final String CHILD_FILTER_EXPRESSION = META_DOC_TYPE + " == '" + DOC_TYPE_CHILD + "'";

    private final VectorStore vectorStore;
    private final EsChunkIndexer esChunkIndexer;
    private final SpotKnowledgeMapper spotKnowledgeMapper;
    private final SpotMapper spotMapper;
    private final RedisTemplate<Object, Object> redisTemplate;
    private final SpotKnowledgeEnricher enricher;
    private final SpotKnowledgeSplitter splitter;

    public ParentChildIndexer(VectorStore vectorStore,
                               EsChunkIndexer esChunkIndexer,
                               SpotKnowledgeMapper spotKnowledgeMapper,
                               SpotMapper spotMapper,
                               RedisTemplate<Object, Object> redisTemplate) {
        this.vectorStore = vectorStore;
        this.esChunkIndexer = esChunkIndexer;
        this.spotKnowledgeMapper = spotKnowledgeMapper;
        this.spotMapper = spotMapper;
        this.redisTemplate = redisTemplate;
        this.enricher = new SpotKnowledgeEnricher();
        this.splitter = new SpotKnowledgeSplitter();
    }

    // ==================== 索引 ====================

    /**
     * 完整索引一个景点的知识文本。
     *
     * @param spot     景点实体
     * @param typeName 景点类型名称
     * @return 索引结果（含父文档全文和子文档数量）
     */
    public IndexingResult indexSpot(Spot spot, String typeName) {
        Long spotId = spot.getId();

        // 先清理旧数据，保证幂等
        deleteFromQdrant(spotId);
        esChunkIndexer.deleteBySpotId(spotId);

        // 1. 富化知识文本
        String enrichedText = enricher.enrich(spot, typeName);
        log.debug("Spot {} 知识文本已富化，长度: {}", spotId, enrichedText.length());

        // 2. 创建父文档
        Map<String, Object> parentMeta = new HashMap<>();
        parentMeta.put(META_DOC_TYPE, DOC_TYPE_PARENT);
        parentMeta.put(META_SPOT_ID, spotId.toString());
        parentMeta.put(META_SPOT_NAME, spot.getName());
        Document parentDoc = new Document(enrichedText, parentMeta);

        // 3. 语义切分为子文档
        List<Document> childDocs = splitter.split(parentDoc);

        // 子文档补充 docType
        for (Document child : childDocs) {
            child.getMetadata().put(META_DOC_TYPE, DOC_TYPE_CHILD);
        }
        log.debug("Spot {} 切分为 {} 个子文档", spotId, childDocs.size());

        // 4. 写入 Qdrant（父文档 + 子文档）
        childDocs.add(0, parentDoc); // 暂将父文档加入写入列表
        vectorStore.add(childDocs);
        childDocs.remove(0); // 恢复 childDocs 为纯子文档列表
        log.debug("Spot {} 已写入 Qdrant: 1个父文档 + {}个子文档", spotId, childDocs.size());

        // 5. 子文档加入 ES BM25 索引
        esChunkIndexer.indexChunks(spotId, spot.getName(), childDocs);
        log.debug("Spot {} 的 {} 个子文档已加入 ES BM25 索引", spotId, childDocs.size());

        // 6. 父文档全文持久化到 MySQL
        SpotKnowledge knowledge = spotKnowledgeMapper.selectById(spotId);
        if (knowledge == null) {
            knowledge = new SpotKnowledge();
            knowledge.setSpotId(spotId);
            knowledge.setKnowledgeText(enrichedText);
            knowledge.setCreateTime(LocalDateTime.now());
            knowledge.setUpdateTime(LocalDateTime.now());
            spotKnowledgeMapper.insert(knowledge);
        } else {
            knowledge.setKnowledgeText(enrichedText);
            knowledge.setUpdateTime(LocalDateTime.now());
            spotKnowledgeMapper.updateById(knowledge);
        }
        log.debug("Spot {} 父文档已持久化到 MySQL", spotId);

        // 7. 父文档全文缓存到 Redis
        redisTemplate.opsForValue().set(REDIS_PARENT_KEY_PREFIX + spotId, enrichedText);
        log.debug("Spot {} 父文档已缓存到 Redis", spotId);

        log.info("ParentChildIndexer 完成索引: spotId={}, childCount={}", spotId, childDocs.size());
        return new IndexingResult(enrichedText, childDocs.size());
    }

    // ==================== 删除 ====================

    /**
     * 清理一个景点的所有索引数据（Qdrant + BM25 + MySQL + Redis）。
     */
    public void deleteSpot(Long spotId) {
        deleteFromQdrant(spotId);
        esChunkIndexer.deleteBySpotId(spotId);
        spotKnowledgeMapper.deleteById(spotId);
        redisTemplate.delete(REDIS_PARENT_KEY_PREFIX + spotId);
        log.info("ParentChildIndexer 完成清理: spotId={}", spotId);
    }

    // ==================== 父文档查询 ====================

    /**
     * 三级查询加载父文档全文：Redis → MySQL → 重建。
     *
     * @param spotId 景点 ID
     * @return 父文档全文（富化后的结构化知识文本）
     */
    public String loadParentText(Long spotId) {
        // ① Redis
        Object cached = redisTemplate.opsForValue().get(REDIS_PARENT_KEY_PREFIX + spotId);
        if (cached != null) {
            log.debug("父文档 Redis 命中: spotId={}", spotId);
            return cached.toString();
        }

        // ② MySQL
        SpotKnowledge knowledge = spotKnowledgeMapper.selectById(spotId);
        if (knowledge != null && knowledge.getKnowledgeText() != null
                && !knowledge.getKnowledgeText().isEmpty()) {
            String text = knowledge.getKnowledgeText();
            // 回写 Redis
            redisTemplate.opsForValue().set(REDIS_PARENT_KEY_PREFIX + spotId, text);
            log.debug("父文档 MySQL 命中并回写 Redis: spotId={}", spotId);
            return text;
        }

        // ③ 重建
        Spot spot = spotMapper.selectById(spotId);
        if (spot == null) {
            log.warn("父文档重建失败: spotId={} 景点不存在", spotId);
            return null;
        }

        // 需要 typeName — 从已有的 Redis 元数据或 SpotKnowledge 中获取
        // 但 enrich 不需要 typeName 作为外部参数，我们可以用 "未知" 作为默认值
        // 更好的做法是重新做一次完整的 index，但这会触发 Qdrant 写入等副作用
        // 所以这里仅做文本重建并持久化，不触发 Qdrant/BM25 的重复写入
        String text = enricher.enrich(spot, "未知");
        log.info("父文档已从 Spot 实体重建: spotId={}", spotId);

        // 持久化重建结果
        SpotKnowledge newKnowledge = new SpotKnowledge();
        newKnowledge.setSpotId(spotId);
        newKnowledge.setKnowledgeText(text);
        newKnowledge.setCreateTime(LocalDateTime.now());
        newKnowledge.setUpdateTime(LocalDateTime.now());
        spotKnowledgeMapper.insert(newKnowledge);

        // 缓存
        redisTemplate.opsForValue().set(REDIS_PARENT_KEY_PREFIX + spotId, text);

        return text;
    }

    // ==================== 内部方法 ====================

    private void deleteFromQdrant(Long spotId) {
        try {
            vectorStore.delete(new FilterExpressionBuilder()
                    .eq(META_SPOT_ID, spotId.toString())
                    .build());
        } catch (Exception e) {
            log.warn("Qdrant 删除异常: spotId={}, error={}", spotId, e.getMessage());
        }
    }

    // ==================== 数据类 ====================

    /** 索引结果 */
    public static class IndexingResult {
        private final String parentText;
        private final int childCount;

        public IndexingResult(String parentText, int childCount) {
            this.parentText = parentText;
            this.childCount = childCount;
        }

        public String getParentText() { return parentText; }
        public int getChildCount() { return childCount; }
    }
}
