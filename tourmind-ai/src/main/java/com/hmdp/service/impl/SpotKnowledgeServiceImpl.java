package com.hmdp.service.impl;

import com.hmdp.entity.Spot;
import com.hmdp.entity.SpotType;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotTypeMapper;
import com.hmdp.rag.chunking.SpotKnowledgeEnricher;
import com.hmdp.rag.chunking.SpotKnowledgeSplitter;
import com.hmdp.rag.index.ParentChildIndexer;
import com.hmdp.service.ISpotKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 景点知识库服务实现 - 负责景点信息向量化
 */
@Slf4j
@Service
public class SpotKnowledgeServiceImpl implements ISpotKnowledgeService {

    private static final String SPOT_KNOWLEDGE_PREFIX = "spot:knowledge:";
    private static final String SPOT_METADATA_PREFIX = "spot:metadata:";

    @Resource
    private SpotMapper spotMapper;

    @Resource
    private SpotTypeMapper spotTypeMapper;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Resource
    private ParentChildIndexer parentChildIndexer;

    /** typeId → typeName 本地缓存 */
    private final ConcurrentHashMap<Long, String> typeNameCache = new ConcurrentHashMap<>();

    /** 知识文本富化器（无状态） */
    private final SpotKnowledgeEnricher enricher = new SpotKnowledgeEnricher();

    /** 语义分块器（无状态） */
    private final SpotKnowledgeSplitter splitter = new SpotKnowledgeSplitter();

    @PostConstruct
    public void loadTypeNames() {
        List<SpotType> types = spotTypeMapper.selectList(null);
        for (SpotType type : types) {
            typeNameCache.put(type.getId(), type.getName());
        }
        log.info("Loaded {} spot type names into cache", types.size());
    }

    @Override
    public void initializeSpotKnowledge(Long spotId) {
        Spot spot = spotMapper.selectById(spotId);
        if (spot == null) {
            log.warn("Spot not found: {}", spotId);
            return;
        }

        // 1. 委托 ParentChildIndexer 做全量索引（父文档+子文档→Qdrant, 子文档→BM25, 父文档→MySQL+Redis）
        String typeName = getTypeName(spot.getTypeId());
        ParentChildIndexer.IndexingResult result = parentChildIndexer.indexSpot(spot, typeName);

        // 2. 保留 Redis 元数据缓存（兼容其他读取路径）
        redisTemplate.opsForValue().set(SPOT_KNOWLEDGE_PREFIX + spotId, result.getParentText());

        // 3. 缓存元数据
        Map<String, String> metadataStr = spotToMetadata(spot);
        redisTemplate.opsForHash().putAll(SPOT_METADATA_PREFIX + spotId, metadataStr);

        log.info("Initialized knowledge for spot: {} - {}, childCount={}",
                spotId, spot.getName(), result.getChildCount());
    }

    @Override
    public void initializeAllSpotKnowledge() {
        List<Spot> spots = spotMapper.selectList(null);
        int count = 0;
        for (Spot spot : spots) {
            try {
                initializeSpotKnowledge(spot.getId());
                count++;
            } catch (Exception e) {
                log.error("Failed to initialize knowledge for spot: {}", spot.getId(), e);
            }
        }
        log.info("Initialized knowledge for {} spots", count);
    }

    @Override
    public void updateSpotKnowledge(Long spotId) {
        // 删除旧知识
        deleteSpotKnowledge(spotId);
        // 重新初始化
        initializeSpotKnowledge(spotId);
    }

    @Override
    public void deleteSpotKnowledge(Long spotId) {
        // 1. 委托 ParentChildIndexer 清理（Qdrant + BM25 + MySQL + Redis 父文档缓存）
        parentChildIndexer.deleteSpot(spotId);

        // 2. 清理 Redis 元数据缓存
        redisTemplate.delete(SPOT_KNOWLEDGE_PREFIX + spotId);
        redisTemplate.delete(SPOT_METADATA_PREFIX + spotId);

        log.info("Deleted knowledge for spot: {} (Qdrant + BM25 + MySQL + Redis)", spotId);
    }

    /**
     * @deprecated 使用 {@link SpotKnowledgeEnricher#enrich(Spot, String)} 替代。
     *             保留此方法仅用于向后兼容，内部委托给 enricher。
     */
    @Deprecated
    private String buildSpotKnowledgeText(Spot spot) {
        return enricher.enrich(spot, getTypeName(spot.getTypeId()));
    }

    private Map<String, String> spotToMetadata(Spot spot) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("name", spot.getName());
        metadata.put("type", getTypeName(spot.getTypeId()));
        metadata.put("area", spot.getArea() != null ? spot.getArea() : "");
        metadata.put("address", spot.getAddress() != null ? spot.getAddress() : "");
        metadata.put("ticketPrice", String.valueOf(spot.getTicketPrice()));
        metadata.put("score", String.valueOf(spot.getScore()));
        metadata.put("openHours", spot.getOpenHours() != null ? spot.getOpenHours() : "");
        if (spot.getX() != null && spot.getY() != null) {
            metadata.put("x", String.valueOf(spot.getX()));
            metadata.put("y", String.valueOf(spot.getY()));
        }
        return metadata;
    }

    private String getTypeName(Long typeId) {
        if (typeId == null) return "未知";
        return typeNameCache.getOrDefault(typeId, "未知");
    }
}
