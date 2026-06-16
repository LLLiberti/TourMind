package com.hmdp.rag.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

/**
 * 检索结果缓存管理器 — 两层 Redis 缓存。
 *
 * <h3>缓存架构</h3>
 * <ul>
 *   <li><b>L1 精确缓存</b> — query MD5 → spotIds JSON，TTL=10min</li>
 *   <li><b>L2 语义缓存</b> — 未命中 L1 时，对 query 去噪后用 MD5 再次尝试，TTL=30min</li>
 * </ul>
 *
 * <h3>缓存失效</h3>
 * <ul>
 *   <li>TTL 自然过期</li>
 *   <li>知识库索引重建时调用 {@link #invalidateAll()} 全量清除</li>
 *   <li>单个景点更新时调用 {@link #invalidateBySpotId(Long)} 清除包含该景点的缓存</li>
 * </ul>
 *
 * <h3>缓存值</h3>
 * <p>存储的是 spotId 列表（JSON 数组），不是 Document 对象。
 * 命中后由调用方从 DB/ThreadLocal 重建 Document，避免 Redis 大 value。</p>
 */
@Slf4j
public class RetrievalCacheManager {

    private static final String CACHE_KEY_PREFIX = "rag:cache:";
    private static final String CACHE_KEY_EXACT = CACHE_KEY_PREFIX + "exact:";
    private static final String CACHE_KEY_SEMANTIC = CACHE_KEY_PREFIX + "semantic:";
    private static final Duration L1_TTL = Duration.ofMinutes(10);
    private static final Duration L2_TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final boolean enabled;

    public RetrievalCacheManager(RedisTemplate<String, String> redisTemplate, boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        log.info("RetrievalCacheManager 初始化: enabled={}", enabled);
    }

    // ==================== 缓存查询 ====================

    /**
     * 查询缓存（L1 → L2 逐级）。
     *
     * @param query 原始查询文本
     * @return 缓存的 spotId 列表，未命中返回 null
     */
    public String get(String query) {
        if (!enabled || query == null || query.isBlank()) return null;

        // L1: 精确匹配
        String exactKey = CACHE_KEY_EXACT + md5(query);
        String value = redisTemplate.opsForValue().get(exactKey);
        if (value != null) {
            log.debug("Cache L1 命中: query='{}'", query);
            return value;
        }

        // L2: 语义匹配（去噪后）
        String normalized = normalize(query);
        if (!normalized.equals(query)) {
            String semanticKey = CACHE_KEY_SEMANTIC + md5(normalized);
            value = redisTemplate.opsForValue().get(semanticKey);
            if (value != null) {
                log.debug("Cache L2 命中: query='{}' (去噪后='{}')", query, normalized);
                // 回写 L1
                redisTemplate.opsForValue().set(exactKey, value, L1_TTL);
                return value;
            }
        }

        log.debug("Cache 未命中: query='{}'", query);
        return null;
    }

    // ==================== 缓存写入 ====================

    /**
     * 写入缓存。
     *
     * @param query   查询文本
     * @param spotIdsJson spotId 列表的 JSON 字符串（如 "[1,2,3]"）
     */
    public void put(String query, String spotIdsJson) {
        if (!enabled || query == null || spotIdsJson == null) return;

        // L1: 精确
        String exactKey = CACHE_KEY_EXACT + md5(query);
        redisTemplate.opsForValue().set(exactKey, spotIdsJson, L1_TTL);

        // L2: 去噪后
        String normalized = normalize(query);
        if (!normalized.equals(query)) {
            String semanticKey = CACHE_KEY_SEMANTIC + md5(normalized);
            redisTemplate.opsForValue().set(semanticKey, spotIdsJson, L2_TTL);
        }

        log.debug("Cache 写入: query='{}'", query);
    }

    // ==================== 缓存失效 ====================

    /** 全量清除（索引重建时调用） */
    public void invalidateAll() {
        if (!enabled) return;
        try {
            var exactKeys = redisTemplate.keys(CACHE_KEY_EXACT + "*");
            var semanticKeys = redisTemplate.keys(CACHE_KEY_SEMANTIC + "*");
            if (exactKeys != null && !exactKeys.isEmpty()) {
                redisTemplate.delete(exactKeys);
            }
            if (semanticKeys != null && !semanticKeys.isEmpty()) {
                redisTemplate.delete(semanticKeys);
            }
            log.info("Cache 全量清除完成");
        } catch (Exception e) {
            log.warn("Cache 全量清除异常: {}", e.getMessage());
        }
    }

    /** 按 spotId 失效（逐个检查缓存值，效率低，适合低频更新） */
    public void invalidateBySpotId(Long spotId) {
        if (!enabled || spotId == null) return;
        try {
            var exactKeys = redisTemplate.keys(CACHE_KEY_EXACT + "*");
            if (exactKeys != null) {
                for (String key : exactKeys) {
                    String value = redisTemplate.opsForValue().get(key);
                    if (value != null && value.contains("\"" + spotId + "\"")
                            || (value != null && value.contains(String.valueOf(spotId)))) {
                        redisTemplate.delete(key);
                        log.debug("Cache 按 spotId 失效: spotId={}, key={}", spotId, key);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Cache 按 spotId 失效异常: {}", e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /** 查询去噪：移除语气词、标点、多余空格 */
    static String normalize(String query) {
        if (query == null) return "";
        return query
                .replaceAll("[？?！!。，,、\\s]+", " ")
                .replaceAll("[的呢吧吗啊呀呗哦噢哈嘿]+", "")
                .trim()
                .toLowerCase();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
