package com.hmdp;

import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotVoucherMapper;
import com.hmdp.service.impl.SpotToolServiceImpl;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SpotToolServiceImpl 单元测试。
 */
@DisplayName("SpotToolServiceImpl")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpotToolServiceTest {

    @Mock
    private SpotMapper spotMapper;

    @Mock
    private SpotVoucherMapper spotVoucherMapper;

    @Mock
    private RedisTemplate<Object, Object> redisTemplate;

    @Mock
    private ValueOperations<Object, Object> valueOperations;

    @InjectMocks
    private SpotToolServiceImpl toolService;

    private Spot spot;

    @BeforeEach
    void setUp() {
        spot = new Spot();
        spot.setId(1L);
        spot.setName("西湖");
        spot.setTicketPrice(80L);  // 80 元

        // 默认：Redis 缓存未命中 → 走 DB 查询 → 回写缓存
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);  // 缓存未命中
    }

    @Nested
    @DisplayName("getSpotPrice")
    class GetSpotPriceTests {

        @Test
        @DisplayName("应返回格式化的价格文本")
        void shouldReturnFormattedPrice() {
            when(spotMapper.selectById(1L)).thenReturn(spot);

            String result = toolService.getSpotPrice(1L);

            assertThat(result).contains("西湖");
            assertThat(result).contains("80 元");
            System.out.println(result);
        }

        @Test
        @DisplayName("spotId 为 null 时应返回错误")
        void shouldReturnErrorForNullSpotId() {
            String result = toolService.getSpotPrice(null);

            assertThat(result).contains("错误");
            assertThat(result).contains("不能为空");
        }

        @Test
        @DisplayName("景点不存在时应返回未找到信息")
        void shouldReturnNotFoundForMissingSpot() {
            when(spotMapper.selectById(999L)).thenReturn(null);

            String result = toolService.getSpotPrice(999L);

            assertThat(result).contains("未找到");
            assertThat(result).contains("999");
        }

        @Test
        @DisplayName("ticketPrice 为 null 时应返回无价格信息")
        void shouldHandleNullPrice() {
            spot.setTicketPrice(null);
            when(spotMapper.selectById(1L)).thenReturn(spot);

            String result = toolService.getSpotPrice(1L);

            assertThat(result).contains("暂无门票价格信息");
        }
    }

    @Nested
    @DisplayName("getSpotVouchers")
    class GetSpotVouchersTests {

        @Test
        @DisplayName("无优惠券时应返回无可用优惠券信息")
        void shouldReturnNoVoucherMessage() {
            when(spotMapper.selectById(1L)).thenReturn(spot);
            when(spotVoucherMapper.queryVouchersBySpotId(1L)).thenReturn(Collections.emptyList());

            String result = toolService.getSpotVouchers(1L);

            assertThat(result).contains("没有可用的优惠券");
        }

        @Test
        @DisplayName("有优惠券时应返回格式化列表")
        void shouldReturnFormattedVoucherList() {
            when(spotMapper.selectById(1L)).thenReturn(spot);
            when(spotVoucherMapper.queryVouchersBySpotId(1L)).thenReturn(List.of(
                    Map.of("title", "满100减20", "payValue", 100L, "actualValue", 80L,
                           "stock", 50, "rules", "每人限购1张",
                           "beginTime", "2026-01-01", "endTime", "2026-12-31")
            ));

            String result = toolService.getSpotVouchers(1L);

            assertThat(result).contains("西湖");
            assertThat(result).contains("满100减20");
            assertThat(result).contains("剩余库存：50 份");
        }

        @Test
        @DisplayName("spotId 为 null 时应返回错误")
        void shouldReturnErrorForNullSpotId() {
            String result = toolService.getSpotVouchers(null);

            assertThat(result).contains("错误");
        }
    }

    @Nested
    @DisplayName("checkVoucherStock")
    class CheckVoucherStockTests {

        @Test
        @DisplayName("应返回实时库存信息")
        void shouldReturnStockInfo() {
            when(spotVoucherMapper.queryStockByVoucherId(100L)).thenReturn(
                    Map.of("voucherId", 100L, "title", "满100减20",
                           "stock", 30, "spotId", 1L)
            );

            String result = toolService.checkVoucherStock(100L);

            assertThat(result).contains("满100减20");
            assertThat(result).contains("30 份");
        }

        @Test
        @DisplayName("库存为 0 时应返回售罄信息")
        void shouldReturnSoldOutForZeroStock() {
            when(spotVoucherMapper.queryStockByVoucherId(100L)).thenReturn(
                    Map.of("voucherId", 100L, "title", "已售罄券",
                           "stock", 0, "spotId", 1L)
            );

            String result = toolService.checkVoucherStock(100L);

            assertThat(result).contains("已售罄");
            assertThat(result).contains("库存为 0");
        }

        @Test
        @DisplayName("优惠券不存在时应返回未找到信息")
        void shouldReturnNotFoundForMissingVoucher() {
            when(spotVoucherMapper.queryStockByVoucherId(999L)).thenReturn(null);

            String result = toolService.checkVoucherStock(999L);

            assertThat(result).contains("未找到");
            assertThat(result).contains("999");
        }

        @Test
        @DisplayName("voucherId 为 null 时应返回错误")
        void shouldReturnErrorForNullVoucherId() {
            String result = toolService.checkVoucherStock(null);

            assertThat(result).contains("错误");
        }
    }
}
