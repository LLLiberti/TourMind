package com.hmdp;

import com.hmdp.service.impl.WeatherServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WeatherServiceImpl 单元测试。
 */
@DisplayName("WeatherServiceImpl")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeatherServiceImplTest {

    @Mock
    private RedisTemplate<Object, Object> redisTemplate;

    @Mock
    private ValueOperations<Object, Object> valueOperations;

    @InjectMocks
    private WeatherServiceImpl weatherService;

    // ==================== 输入校验 ====================

    @Nested
    @DisplayName("getWeather — 输入校验")
    class InputValidationTests {

        @Test
        @DisplayName("location 为 null 时应返回错误")
        void shouldReturnErrorForNullLocation() {
            String result = weatherService.getWeather(null, 30.0, 120.0);

            assertThat(result).contains("错误");
            assertThat(result).contains("不能为空");
        }

        @Test
        @DisplayName("location 为空字符串时应返回错误")
        void shouldReturnErrorForBlankLocation() {
            // 空字符串在第一行短路返回，不会触发缓存/API 调用，无需 mock
            String result = weatherService.getWeather("   ", 30.0, 120.0);

            assertThat(result).contains("错误");
            assertThat(result).contains("不能为空");
        }
    }

    // ==================== 缓存命中 ====================

    @Nested
    @DisplayName("getWeather — 缓存机制")
    class CacheTests {

        @Test
        @DisplayName("Redis 缓存命中时应直接返回缓存内容")
        void shouldReturnCachedValue() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            String cached = """
                    【西湖】实时天气
                    天气：晴天 ☀️
                    温度：25.0°C（体感 26.0°C）
                    湿度：60%
                    风速：10.0 km/h""";
            when(valueOperations.get(anyString())).thenReturn(cached);

            String result = weatherService.getWeather("西湖", 30.23, 120.14);

            assertThat(result).contains("晴天 ☀️");
            assertThat(result).contains("25.0°C");
            System.out.println("[缓存命中] " + result);
        }
    }
}
