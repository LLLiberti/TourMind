package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 景点知识文本持久化实体 — 存储富化后的完整知识文本（父文档）。
 *
 * <p>查询路径：Redis → MySQL → Spot实体重建，保证父文档的高可用。</p>
 */
@Data
@TableName("tb_spot_knowledge")
public class SpotKnowledge implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 景点id（与 tb_spot.id 一一对应）
     */
    @TableId(value = "spot_id", type = IdType.INPUT)
    private Long spotId;

    /**
     * 富化后的完整知识文本（父文档全文，含 [景点简介][位置交通][开放须知] 等语义标签）
     */
    private String knowledgeText;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
