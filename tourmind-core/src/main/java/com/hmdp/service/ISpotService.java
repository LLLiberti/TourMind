package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Spot;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ISpotService extends IService<Spot> {

    Object queryById(Long id);

    Result update(Spot spot);

    Result saveSpot(Spot spot);

    Result querySpotByType(Integer typeId, Integer current, Double x, Double y);
}
