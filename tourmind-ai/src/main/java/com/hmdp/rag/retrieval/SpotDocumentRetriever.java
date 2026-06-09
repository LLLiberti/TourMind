package com.hmdp.rag.retrieval;

import com.hmdp.config.RagConfig;
import com.hmdp.entity.Spot;
import com.hmdp.entity.SpotType;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotTypeMapper;
import com.hmdp.rag.RetrievalContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 自定义景点文档检索器 — 实现 Spring AI 模块化 RAG 的 {@link DocumentRetriever} 接口。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>向量检索：通过 VectorStore.similaritySearch() 查相似文档</li>
 *   <li>实体映射：从文档 metadata 提取 spotId，批量 DB 查询获取完整 Spot</li>
 *   <li>距离排序：若 ThreadLocal 中有用户坐标，按欧几里得距离排序</li>
 *   <li>结果缓存：通过 ThreadLocal 将 Spot 列表回传给 Service 层（构建 DTO）</li>
 *   <li>文档输出：将 Spot 转为富元数据的 Document 列表，供 ContextualQueryAugmenter 注入 LLM</li>
 * </ol>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 1. 设置用户坐标（可选）
 * spotDocumentRetriever.setUserCoordinates(121.50, 31.23);
 * // 2. ChatClient 的 RetrievalAugmentationAdvisor 会自动调用 retrieve()
 * String answer = chatClient.prompt().user("推荐附近的川菜馆").call().content();
 * // 3. 获取检索到的 Spot（用于 API 响应 DTO）
 * List<Spot> spots = spotDocumentRetriever.getLastRetrievedSpots();
 * // 4. 清理
 * spotDocumentRetriever.clearContext();
 * }</pre>
 */
@Slf4j
public class SpotDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final SpotMapper spotMapper;
    private final SpotTypeMapper spotTypeMapper;
    private final RagConfig ragConfig;

    /** typeId → typeName 本地缓存，@PostConstruct 加载 */
    private final ConcurrentHashMap<Long, String> typeNameCache = new ConcurrentHashMap<>();

    /** Per-request ThreadLocal 上下文 */
    private final ThreadLocal<RetrievalContext> contextHolder = new ThreadLocal<>();

    public SpotDocumentRetriever(VectorStore vectorStore, SpotMapper spotMapper,
                                  SpotTypeMapper spotTypeMapper, RagConfig ragConfig) {
        this.vectorStore = vectorStore;
        this.spotMapper = spotMapper;
        this.spotTypeMapper = spotTypeMapper;
        this.ragConfig = ragConfig;
    }

    @PostConstruct
    public void loadTypeNames() {
        List<SpotType> types = spotTypeMapper.selectList(null);
        for (SpotType type : types) {
            typeNameCache.put(type.getId(), type.getName());
        }
        log.info("SpotDocumentRetriever 加载了 {} 个景点类型", types.size());
    }

    // ==================== DocumentRetriever 接口实现 ====================

    @Override
    public List<Document> retrieve(Query query) {
        String question = query.text();
        log.debug("SpotDocumentRetriever.retrieve() 开始，query={}", question);

        // 1. 向量检索（多取一些以便后续过滤和排序）
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(ragConfig.getMaxContextSpots() * 2)
                .similarityThreshold(ragConfig.getSimilarityThreshold())
                .build();

        List<Document> vectorDocs = vectorStore.similaritySearch(searchRequest);
        log.debug("向量检索命中 {} 条", vectorDocs.size());

        if (vectorDocs.isEmpty()) {
            clearContext();
            return Collections.emptyList();
        }

        // 2. 提取 spotId 并批量 DB 查询（保持向量检索的顺序）
        Set<Long> spotIds = vectorDocs.stream()
                .map(doc -> {
                    Object spotIdObj = doc.getMetadata().get("spotId");
                    if (spotIdObj == null) return null;
                    try {
                        return Long.valueOf(spotIdObj.toString());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (spotIds.isEmpty()) {
            clearContext();
            return Collections.emptyList();
        }

        List<Spot> spots = spotMapper.selectBatchIds(spotIds);

        // 保持向量检索的顺序
        Map<Long, Spot> spotMap = spots.stream()
                .collect(Collectors.toMap(Spot::getId, Function.identity(), (a, b) -> a));
        List<Spot> orderedSpots = spotIds.stream()
                .map(spotMap::get)
                .filter(Objects::nonNull)
                .limit(ragConfig.getMaxContextSpots())
                .collect(Collectors.toList());

        // 3. 距离排序（如果有用户坐标）
        RetrievalContext ctx = contextHolder.get();
        if (ctx == null) {
            ctx = new RetrievalContext();
            contextHolder.set(ctx);
        }
        if (ctx.getUserX() != null && ctx.getUserY() != null) {
            orderedSpots = sortByDistance(orderedSpots, ctx.getUserX(), ctx.getUserY());
        }

        // 4. 缓存 Spot 列表供 Service 层构建 DTO
        ctx.setRetrievedSpots(orderedSpots);

        // 5. 将 Spot 转为带富元数据的 Document
        List<Document> enrichedDocs = orderedSpots.stream()
                .map(this::spotToDocument)
                .collect(Collectors.toList());

        log.debug("SpotDocumentRetriever.retrieve() 完成，输出 {} 个文档", enrichedDocs.size());
        return enrichedDocs;
    }

    // ==================== 供 Service 层调用的 API ====================

    /**
     * 设置当前请求的用户坐标（用于距离排序）
     */
    public void setUserCoordinates(Double userX, Double userY) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setUserX(userX);
        ctx.setUserY(userY);
        contextHolder.set(ctx);
    }

    /**
     * 获取最近一次检索到的 Spot 列表（含距离排序结果）
     * 供 Service 层在 ChatClient 调用后提取，构建 API 响应中的 recommendedSpots DTO
     */
    public List<Spot> getLastRetrievedSpots() {
        RetrievalContext ctx = contextHolder.get();
        return ctx != null ? ctx.getRetrievedSpots() : Collections.emptyList();
    }

    /**
     * 清理当前请求的 ThreadLocal 上下文
     * 必须在 Service 层的 finally 块中调用，防止内存泄漏
     */
    public void clearContext() {
        contextHolder.remove();
    }

    // ==================== 内部方法 ====================

    /**
     * 将 Spot 实体转为 Spring AI Document（含完整元数据）
     * ContextualQueryAugmenter 会将 Document 内容作为上下文注入到 prompt 中
     */
    private Document spotToDocument(Spot spot) {
        String typeName = getTypeName(spot.getTypeId());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("spotId", spot.getId().toString());
        metadata.put("spotName", spot.getName());
        metadata.put("typeName", typeName);
        if (spot.getArea() != null) {
            metadata.put("area", spot.getArea());
        }
        if (spot.getAddress() != null) {
            metadata.put("address", spot.getAddress());
        }
        metadata.put("ticketPrice", spot.getTicketPrice());
        metadata.put("score", spot.getScore());
        if (spot.getOpenHours() != null) {
            metadata.put("openHours", spot.getOpenHours());
        }
        if (spot.getX() != null && spot.getY() != null) {
            metadata.put("x", spot.getX());
            metadata.put("y", spot.getY());
        }
        if (spot.getDistance() != null) {
            metadata.put("distance", spot.getDistance());
        }

        String content = buildSpotContent(spot, typeName);
        return new Document(content, metadata);
    }

    /**
     * 构建单个景点的上下文文本（注入 LLM prompt）。
     *
     * <p><b>注意：不包含门票价格。</b>价格等实时数据由 Function Calling 工具提供。</p>
     */
    private String buildSpotContent(Spot spot, String typeName) {
        StringBuilder sb = new StringBuilder();
        sb.append("景点名称：").append(spot.getName()).append("\n");
        sb.append("景点类型：").append(typeName).append("\n");
        if (spot.getArea() != null) {
            sb.append("所在区域：").append(spot.getArea()).append("\n");
        }
        if (spot.getAddress() != null) {
            sb.append("具体地址：").append(spot.getAddress()).append("\n");
        }
        // ticketPrice 不写入上下文 — 实时价格由 Function Calling 工具获取
        sb.append("评分：").append(spot.getScore()).append("分\n");
        if (spot.getOpenHours() != null) {
            sb.append("开放时间：").append(spot.getOpenHours()).append("\n");
        }
        if (spot.getDistance() != null) {
            sb.append(String.format("距离：%.2f 公里\n", spot.getDistance()));
        }
        return sb.toString();
    }

    private String getTypeName(Long typeId) {
        if (typeId == null) return "未知";
        return typeNameCache.getOrDefault(typeId, "未知");
    }

    /**
     * 按欧几里得距离排序
     */
    private List<Spot> sortByDistance(List<Spot> spots, Double userX, Double userY) {
        return spots.stream()
                .peek(spot -> {
                    if (spot.getX() != null && spot.getY() != null) {
                        double distance = calculateDistance(userX, userY, spot.getX(), spot.getY());
                        spot.setDistance(distance);
                    }
                })
                .sorted(Comparator.comparing(Spot::getDistance,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /**
     * 计算两点间的欧几里得距离
     */
    private double calculateDistance(Double x1, Double y1, Double x2, Double y2) {
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return Double.MAX_VALUE;
        }
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
