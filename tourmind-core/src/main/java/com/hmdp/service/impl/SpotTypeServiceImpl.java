package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Spot;
import com.hmdp.entity.SpotType;
import com.hmdp.mapper.SpotTypeMapper;
import com.hmdp.service.ISpotTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.constant.RedisConstant.CACHE_NULL_TTL;
import static com.hmdp.constant.RedisConstant.CACHE_SPOT_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class SpotTypeServiceImpl extends ServiceImpl<SpotTypeMapper, SpotType> implements ISpotTypeService {

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Override
    public Object querySpotTypeList() {

        String jsonStr = (String) redisTemplate.opsForValue().get(CACHE_SPOT_TYPE_KEY);

        if (StrUtil.isNotBlank(jsonStr)) {
            // 将 JSON 字符串反序列化为 List<SpotType>
            List<SpotType> shopTypes = JSONUtil.toList(jsonStr, SpotType.class);
            return Result.ok(shopTypes);
        }

        // 缓存不存在则查询数据库
        List<SpotType> typeList = query().orderByAsc("sort").list();

        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("类型不存在");
        }

        // 写入缓存：将 List 转为 JSON 字符串再存储
        String jsonCache = JSONUtil.toJsonStr(typeList);
        redisTemplate.opsForValue().set(CACHE_SPOT_TYPE_KEY, jsonCache);


        return Result.ok(typeList);

    }
}
