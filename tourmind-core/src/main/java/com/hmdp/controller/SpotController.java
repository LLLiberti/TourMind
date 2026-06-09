package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Spot;
import com.hmdp.service.ISpotService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;


/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/spot")
public class SpotController {

    @Resource
    public ISpotService spotService;

    /**
     * 根据id查询景点信息
     * @param id 景点id
     * @return 景点详情数据
     */
    @GetMapping("/{id}")
    public Result querySpotById(@PathVariable("id") Long id) {
        return Result.ok(spotService.queryById(id));
    }

    /**
     * 新增景点信息
     * @param spot 景点数据
     * @return 景点id
     */
    @PostMapping
    public Result saveSpot(@RequestBody Spot spot) {
        // 返回店铺id
        return spotService.saveSpot(spot);
    }

    /**
     * 更新景点信息
     * @param spot 景点数据
     * @return 无
     */
    @PutMapping
    public Result updateSpot(@RequestBody Spot spot) {
        // 写入数据库
        ;
        return spotService.update(spot);
    }

    /**
     * 根据景点类型分页查询景点信息
     * @param typeId 景点类型
     * @param current 页码
     * @return 景点列表
     */
    @GetMapping("/of/type")
    public Result querySpotByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        return spotService.querySpotByType(typeId, current, x, y);
    }

    /**
     * 根据景点名称关键字分页查询景点信息
     * @param name 景点名称关键字
     * @param current 页码
     * @return 景点列表
     */
    @GetMapping("/of/name")
    public Result querySpotByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型关键字分页查询
        Page<Spot> page = spotService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }

}
