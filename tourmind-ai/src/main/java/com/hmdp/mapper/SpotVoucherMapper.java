package com.hmdp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 景点优惠券查询 Mapper — 使用注解 SQL，不依赖 tourmind-core 的 XML。
 *
 * <p>查询 {@code tb_voucher} 和 {@code tb_seckill_voucher} 表，
 * 为 Function Calling 工具提供优惠券和库存数据。</p>
 */
@Mapper
public interface SpotVoucherMapper {

    /**
     * 查询景点可用的优惠券列表（含秒杀库存信息）。
     *
     * @param spotId 景点 ID
     * @return 优惠券列表，每项为 Map（key 为列名小写）
     */
    @Select("SELECT v.id, v.shop_id AS spotId, v.title, v.sub_title AS subTitle, " +
            "v.rules, v.pay_value AS payValue, v.actual_value AS actualValue, " +
            "v.type, v.status, " +
            "sv.stock, sv.begin_time AS beginTime, sv.end_time AS endTime " +
            "FROM tb_voucher v " +
            "LEFT JOIN tb_seckill_voucher sv ON v.id = sv.voucher_id " +
            "WHERE v.shop_id = #{spotId} AND v.status = 1")
    List<Map<String, Object>> queryVouchersBySpotId(@Param("spotId") Long spotId);

    /**
     * 查询秒杀优惠券的实时库存。
     *
     * @param voucherId 优惠券 ID
     * @return 库存信息 Map（stock, beginTime, endTime 等），未找到返回 null
     */
    @Select("SELECT sv.voucher_id AS voucherId, sv.stock, sv.begin_time AS beginTime, " +
            "sv.end_time AS endTime, v.title, v.shop_id AS spotId " +
            "FROM tb_seckill_voucher sv " +
            "JOIN tb_voucher v ON sv.voucher_id = v.id " +
            "WHERE sv.voucher_id = #{voucherId}")
    Map<String, Object> queryStockByVoucherId(@Param("voucherId") Long voucherId);
}
