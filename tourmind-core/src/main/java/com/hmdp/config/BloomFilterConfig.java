package com.hmdp.config;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.hmdp.entity.Spot;
import com.hmdp.service.ISpotService;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.hmdp.constant.RedisConstant.CACHE_SPOT_KEY;

/**
 * @author lbq
 */
@Configuration
public class BloomFilterConfig {


    @Bean
    public BloomFilter<String> spotBloomFilter() {
        // 预期插入数量和误判率
        BloomFilter<String> bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                100000,
                0.01
        );
        return bloomFilter;
    }
}
