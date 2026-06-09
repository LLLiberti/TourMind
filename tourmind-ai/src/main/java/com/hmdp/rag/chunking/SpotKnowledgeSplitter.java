package com.hmdp.rag.chunking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 景点知识文本语义分块器 — 按语义标题（[标签]）切分富化后的知识文本。
 *
 * <h3>切分策略</h3>
 * <p>按 {@code \n(?=\[)} 正则，在语义标签前分割：</p>
 * <pre>{@code
 * [景点简介]         → chunk 0, topic="简介"
 * 名称：西湖...
 *
 * [位置交通]         → chunk 1, topic="位置"
 * 区域：西湖区...
 *
 * [开放须知]         → chunk 2, topic="须知"
 * 开放时间：全天
 * }</pre>
 *
 * <h3>元数据继承</h3>
 * <p>每个 chunk 继承父 Document 的 spotId、spotName，并新增：</p>
 * <ul>
 *   <li>{@code chunkTopic} — 从标题提取的语义主题（如"简介""位置""须知"）</li>
 *   <li>{@code chunkIndex} — 分块序号（0-based）</li>
 * </ul>
 *
 * <h3>兼容性</h3>
 * <p>若文本无标题标记（如旧格式数据），返回仅含原始 Document 的列表。</p>
 */
@Slf4j
public class SpotKnowledgeSplitter {

    /** 在语义标签（[xxx]）前分割的正则 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("\\n(?=\\[)");

    /** 从标题中提取纯文本主题，如 "[景点简介]" → "简介" */
    private static final Pattern TOPIC_EXTRACT_PATTERN = Pattern.compile("\\[([^]]+)\\]");

    /**
     * 将富化后的知识文本 Document 按语义标题切分为多个 chunk Document。
     *
     * @param document 包含富化知识文本的 Document（含 spotId、spotName 元数据）
     * @return 切分后的 Document 列表；若无标题标记则返回仅含原 Document 的列表
     */
    public List<Document> split(Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            log.warn("Document 内容为空，返回空列表");
            return List.of();
        }

        // 检测是否包含语义标题标记
        if (!HEADING_PATTERN.matcher(text).find() && !text.startsWith("[")) {
            // 无任何标题标记，返回原 Document（向后兼容旧格式）
            log.debug("文本无标题标记，返回原 Document");
            return List.of(document);
        }

        String[] sections = HEADING_PATTERN.split(text);

        Map<String, Object> parentMetadata = document.getMetadata();
        List<Document> chunks = new ArrayList<>(sections.length);

        for (int i = 0; i < sections.length; i++) {
            String section = sections[i].trim();
            if (section.isEmpty()) {
                continue;
            }

            // 提取语义主题
            String topic = extractTopic(section);

            // 构建 chunk 元数据：继承父元数据 + 新增 chunk 信息
            Map<String, Object> chunkMetadata = new HashMap<>(parentMetadata);
            chunkMetadata.put("chunkTopic", topic);
            chunkMetadata.put("chunkIndex", i);

            Document chunk = new Document(section, chunkMetadata);
            chunks.add(chunk);
        }

        log.debug("已将 Document (spotId={}) 切分为 {} 个 chunk",
                parentMetadata.get("spotId"), chunks.size());
        return chunks;
    }

    /**
     * 从文本片段的首行标题中提取语义主题。
     * <p>例如：{@code "[景点简介]\n景点名称：西湖"} → {@code "简介"}</p>
     */
    private String extractTopic(String text) {
        Matcher m = TOPIC_EXTRACT_PATTERN.matcher(text);
        if (m.find()) {
            String full = m.group(1); // 如 "景点简介"
            // 去掉常见前缀"景点"，返回简洁主题名
            if (full.startsWith("景点")) {
                return full.substring(2);
            }
            return full;
        }
        return "未知";
    }
}
