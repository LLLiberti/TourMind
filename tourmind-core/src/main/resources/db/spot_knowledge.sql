-- ============================================================
-- TourMind 景点知识文本持久化表
-- 存储富化后的完整知识文本（父文档），查询路径：Redis → MySQL → 重建
-- ============================================================
DROP TABLE IF EXISTS `tb_spot_knowledge`;
CREATE TABLE `tb_spot_knowledge` (
  `spot_id` bigint(20) UNSIGNED NOT NULL COMMENT '景点id（与 tb_spot.id 一一对应）',
  `knowledge_text` text NOT NULL COMMENT '富化后的完整知识文本（含[景点简介][位置交通][开放须知]等语义标签）',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`spot_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='景点知识文本持久化表';
