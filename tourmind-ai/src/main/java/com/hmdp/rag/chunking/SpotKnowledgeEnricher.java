package com.hmdp.rag.chunking;

import com.hmdp.entity.Spot;

/**
 * 景点知识文本富化器 — 将 Spot 实体转为带语义标签的结构化知识文本。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>按语义主题分段（简介、位置、须知），便于后续分块检索</li>
 *   <li><b>不包含门票价格</b> — 实时价格走 Function Calling 业务接口</li>
 *   <li>score（内部整数×10）转换为可读的 "X.X/5.0分" 格式</li>
 *   <li>无状态纯函数，线程安全</li>
 * </ul>
 *
 * <h3>输出格式</h3>
 * <pre>{@code
 * [景点简介]
 * 景点名称：西湖
 * 景点类型：自然风景区
 * 评分：4.5/5.0分
 *
 * [位置交通]
 * 所在区域：西湖区
 * 具体地址：杭州市西湖区西湖风景区
 *
 * [开放须知]
 * 开放时间：全天
 *
 * [景点特色]
 * 湖光山色冠绝天下...
 * }</pre>
 * <p><b>注意：购票须知和退票条件不写入主知识库，由 TicketRefundEnricher 写入退购票专项知识库。</b></p>
 */
public class SpotKnowledgeEnricher {

    /**
     * 将 Spot 实体富化为带语义标签的结构化知识文本。
     *
     * @param spot     景点实体
     * @param typeName 景点类型名称（由调用方从缓存中获取）
     * @return 富化后的知识文本，不含价格信息
     */
    public String enrich(Spot spot, String typeName) {
        StringBuilder sb = new StringBuilder();

        // [景点简介] — 基础介绍信息
        sb.append("[景点简介]\n");
        sb.append("景点名称：").append(spot.getName()).append("\n");
        sb.append("景点类型：").append(typeName != null ? typeName : "未知").append("\n");
        sb.append("评分：").append(formatScore(spot.getScore())).append("\n");

        // [位置交通] — 地理位置信息
        sb.append("\n[位置交通]\n");
        if (spot.getArea() != null && !spot.getArea().isEmpty()) {
            sb.append("所在区域：").append(spot.getArea()).append("\n");
        }
        if (spot.getAddress() != null && !spot.getAddress().isEmpty()) {
            sb.append("具体地址：").append(spot.getAddress()).append("\n");
        }
        if (spot.getX() != null && spot.getY() != null) {
            sb.append(String.format("地理坐标：东经%.4f°，北纬%.4f°\n", spot.getX(), spot.getY()));
        }

        // [开放须知] — 游览相关信息
        sb.append("\n[开放须知]\n");
        if (spot.getOpenHours() != null && !spot.getOpenHours().isEmpty()) {
            sb.append("开放时间：").append(spot.getOpenHours()).append("\n");
        } else {
            sb.append("开放时间：请咨询景区\n");
        }

        // [景点特色] — 核心亮点描述
        if (spot.getFeatures() != null && !spot.getFeatures().isEmpty()) {
            sb.append("\n[景点特色]\n");
            sb.append(spot.getFeatures()).append("\n");
        }

        // 注意：购票须知和退票条件不在此处写入，
        // 由 TicketRefundEnricher 独立构建，存入退购票专项知识库

        return sb.toString();
    }

    /**
     * 格式化评分 — 内部整数（×10）→ 可读字符串。
     * <p>例如：45 → "4.5/5.0分"，null → "暂无评分"</p>
     */
    private String formatScore(Integer score) {
        if (score == null) {
            return "暂无评分";
        }
        // score 范围通常是 0-50，映射到 0.0-5.0
        double s = score / 10.0;
        return String.format("%.1f/5.0分", s);
    }
}
