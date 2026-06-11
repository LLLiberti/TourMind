package com.hmdp.service;

/**
 * 天气查询服务 — 为 LLM Function Calling 提供实时天气数据。
 *
 * <h3>数据来源</h3>
 * <p>使用 Open-Meteo 免费天气 API（无需 API Key），通过经纬度查询实时天气。
 * 当坐标不可用时，通过 Open-Meteo Geocoding API 将地名解析为坐标。</p>
 *
 * <h3>缓存策略</h3>
 * <p>天气数据通过 Redis 缓存，同一坐标 30 分钟内复用，避免频繁调用外部 API。</p>
 *
 * <h3>返回格式</h3>
 * <p>返回 LLM 可直接阅读的结构化文本，包含温度、体感温度、湿度、风速、天气现象。</p>
 */
public interface IWeatherService {

    /**
     * 查询指定地点的实时天气。
     *
     * @param location 地点名称，如"西湖"、"杭州"
     * @param lat      纬度（可为 null，此时通过地名地理编码获取坐标）
     * @param lon      经度（可为 null）
     * @return 格式化天气文本
     */
    String getWeather(String location, Double lat, Double lon);
}
