package com.hmdp.dto;

import com.hmdp.entity.Spot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 景点响应 DTO — 用于 API 返回给前端的景点数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpotDTO {

    /** 景点 ID */
    private Long id;

    /** 景点名称 */
    private String name;

    /** 所在区域（如"西湖"） */
    private String area;

    /** 详细地址 */
    private String address;

    /** 门票价格（元） */
    private Long ticketPrice;

    /** 评分（1~5 分） */
    private Integer score;

    /** 开放时间（如 08:00-18:00） */
    private String openHours;

    /** 距用户距离（公里），仅当传入用户坐标时有效 */
    private Double distance;

    /**
     * 从 Spot 实体构建 SpotDTO
     */
    public static SpotDTO from(Spot spot) {
        return SpotDTO.builder()
                .id(spot.getId())
                .name(spot.getName())
                .area(spot.getArea())
                .address(spot.getAddress())
                .ticketPrice(spot.getTicketPrice())
                .score(spot.getScore())
                .openHours(spot.getOpenHours())
                .distance(spot.getDistance())
                .build();
    }
}
