package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.IWeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询服务实现 — 调用 Open-Meteo 免费 API，Redis 缓存结果。
 *
 * <h3>数据流</h3>
 * <ol>
 *   <li>优先使用传入的经纬度坐标</li>
 *   <li>坐标缺失时通过 Open-Meteo Geocoding API 将地名解析为坐标</li>
 *   <li>查 Redis 缓存（key: {@code weather:{lat}:{lon}}，TTL 30 分钟）</li>
 *   <li>缓存未命中 → 调用 Open-Meteo Forecast API → 回写缓存</li>
 *   <li>WMO 天气码 → 中文描述 + emoji</li>
 * </ol>
 *
 * <h3>API 说明</h3>
 * <ul>
 *   <li>Forecast: {@code https://api.open-meteo.com/v1/forecast}</li>
 *   <li>Geocoding: {@code https://geocoding-api.open-meteo.com/v1/search}</li>
 *   <li>完全免费，无需 API Key，支持全球任何经纬度</li>
 * </ul>
 */
@Slf4j
@Service
public class WeatherServiceImpl implements IWeatherService {

    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast"
            + "?latitude={lat}&longitude={lon}"
            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
            + "&timezone=Asia%2FShanghai";

    private static final String GEOCODING_URL =
            "https://geocoding-api.open-meteo.com/v1/search"
            + "?name={name}&count=1&language=zh";

    private static final String WEATHER_CACHE_KEY = "weather:";

    /** 天气缓存 TTL：30 分钟 */
    private static final long WEATHER_CACHE_TTL = 30;

    /** 坐标精度：保留 2 位小数作为缓存 key */
    private static final int COORD_SCALE = 2;

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== WMO 天气码 → 中文描述 ====================

    private static final Map<Integer, String> WEATHER_CODE_MAP = Map.ofEntries(
            Map.entry(0, "晴天 ☀️"),
            Map.entry(1, "少云 🌤️"),
            Map.entry(2, "晴间多云 ⛅"),
            Map.entry(3, "多云 ☁️"),
            Map.entry(45, "有雾 🌫️"),
            Map.entry(48, "雾凇 🌫️"),
            Map.entry(51, "小毛毛雨 🌦️"),
            Map.entry(53, "毛毛雨 🌦️"),
            Map.entry(55, "大毛毛雨 🌧️"),
            Map.entry(56, "冻毛毛雨 🌧️"),
            Map.entry(57, "大冻毛毛雨 🌧️"),
            Map.entry(61, "小雨 🌧️"),
            Map.entry(63, "中雨 🌧️"),
            Map.entry(65, "大雨 🌧️"),
            Map.entry(66, "冻雨 🌧️"),
            Map.entry(67, "大冻雨 🌧️"),
            Map.entry(71, "小雪 🌨️"),
            Map.entry(73, "中雪 🌨️"),
            Map.entry(75, "大雪 🌨️"),
            Map.entry(77, "雪粒 ❄️"),
            Map.entry(80, "阵雨 🌦️"),
            Map.entry(81, "中阵雨 🌧️"),
            Map.entry(82, "大阵雨 🌧️"),
            Map.entry(85, "小阵雪 🌨️"),
            Map.entry(86, "大阵雪 🌨️"),
            Map.entry(95, "雷暴 ⛈️"),
            Map.entry(96, "小冰雹雷暴 ⛈️"),
            Map.entry(99, "大冰雹雷暴 ⛈️")
    );

    @Override
    public String getWeather(String location, Double lat, Double lon) {
        if (location == null || location.isBlank()) {
            return "错误：查询地点不能为空";
        }

        try {
            // Step 1: 解析坐标
            double[] coords = resolveCoordinates(location, lat, lon);
            if (coords == null) {
                return String.format("无法解析地点【%s】的坐标，请尝试输入更具体的地点名称", location);
            }

            double resolvedLat = coords[0];
            double resolvedLon = coords[1];

            // Step 2: 查 Redis 缓存
            String cacheKey = buildCacheKey(resolvedLat, resolvedLon);
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof String cachedText && !cachedText.isEmpty()) {
                log.debug("天气缓存命中: {} ({},{})", location, resolvedLat, resolvedLon);
                return cachedText;
            }

            // Step 3: 调用 Open-Meteo Forecast API
            String forecastJson = restTemplate.getForObject(FORECAST_URL, String.class,
                    resolvedLat, resolvedLon);

            if (forecastJson == null || forecastJson.isBlank()) {
                return String.format("未能获取地点【%s】的天气数据，请稍后重试", location);
            }

            // Step 4: 解析并格式化
            String weatherText = parseAndFormat(location, forecastJson);

            // Step 5: 回写缓存
            redisTemplate.opsForValue().set(cacheKey, weatherText, WEATHER_CACHE_TTL, TimeUnit.MINUTES);
            log.debug("天气缓存回写: {} ({} min)", cacheKey, WEATHER_CACHE_TTL);

            return weatherText;

        } catch (Exception e) {
            log.error("天气查询失败 location={}, lat={}, lon={}", location, lat, lon, e);
            return String.format("查询地点【%s】的天气时发生错误，请稍后重试", location);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析坐标：优先使用传入的经纬度，否则通过地理编码获取。
     */
    private double[] resolveCoordinates(String location, Double lat, Double lon) {
        if (lat != null && lon != null
                && lat >= -90 && lat <= 90
                && lon >= -180 && lon <= 180) {
            return new double[]{lat, lon};
        }

        // Geocoding 兜底
        try {
            String geoJson = restTemplate.getForObject(GEOCODING_URL, String.class, location);
            if (geoJson == null) return null;

            JsonNode root = objectMapper.readTree(geoJson);
            JsonNode results = root.path("results");
            if (results.isEmpty()) return null;

            JsonNode first = results.get(0);
            double geoLat = first.path("latitude").asDouble();
            double geoLon = first.path("longitude").asDouble();

            if (geoLat == 0 && geoLon == 0) return null;

            log.info("地理编码: {} → ({}, {})", location, geoLat, geoLon);
            return new double[]{geoLat, geoLon};
        } catch (Exception e) {
            log.warn("地理编码失败: {}", location, e);
            return null;
        }
    }

    /**
     * 解析 Open-Meteo JSON 并格式化为中文天气描述。
     */
    private String parseAndFormat(String location, String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode current = root.path("current");

        double temperature = current.path("temperature_2m").asDouble();
        double apparentTemp = current.path("apparent_temperature").asDouble();
        int humidity = current.path("relative_humidity_2m").asInt();
        double windSpeed = current.path("wind_speed_10m").asDouble();
        int weatherCode = current.path("weather_code").asInt();

        String weatherDesc = WEATHER_CODE_MAP.getOrDefault(weatherCode, "未知天气 (code=" + weatherCode + ")");

        // 温度保留 1 位小数
        BigDecimal temp = BigDecimal.valueOf(temperature).setScale(1, RoundingMode.HALF_UP);
        BigDecimal feelsLike = BigDecimal.valueOf(apparentTemp).setScale(1, RoundingMode.HALF_UP);

        return String.format("""
                【%s】实时天气
                天气：%s
                温度：%s°C（体感 %s°C）
                湿度：%d%%
                风速：%.1f km/h""",
                location, weatherDesc, temp, feelsLike, humidity, windSpeed);
    }

    /**
     * 构建缓存 key：{@code weather:{lat}:{lon}}，坐标四舍五入到 2 位小数。
     */
    private String buildCacheKey(double lat, double lon) {
        BigDecimal latBd = BigDecimal.valueOf(lat).setScale(COORD_SCALE, RoundingMode.HALF_UP);
        BigDecimal lonBd = BigDecimal.valueOf(lon).setScale(COORD_SCALE, RoundingMode.HALF_UP);
        return WEATHER_CACHE_KEY + latBd + ":" + lonBd;
    }
}
