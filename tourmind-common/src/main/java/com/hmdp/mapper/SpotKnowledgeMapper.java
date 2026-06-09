package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.SpotKnowledge;

/**
 * 景点知识文本 Mapper — 操作 {@code tb_spot_knowledge} 表。
 *
 * <p>父文档全文的持久化存储，查询路径：Redis → MySQL → 重建。</p>
 */
public interface SpotKnowledgeMapper extends BaseMapper<SpotKnowledge> {
}
