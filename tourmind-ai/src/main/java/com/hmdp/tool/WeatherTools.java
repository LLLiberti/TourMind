package com.hmdp.tool;

import com.hmdp.service.IWeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 天气查询工具 — 通过 Spring AI {@link Tool @Tool} 注解暴露给 LLM。
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>{@code checkWeather} — 查询指定地点的实时天气（温度、湿度、风速、天气现象）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <p>在 {@code AIConfig} 中通过 {@code .defaultTools(spotTools, weatherTools)} 注册到 ChatClient。
 * LLM 根据用户问题自动决定是否调用此工具。</p>
 *
 * <h3>调用时机</h3>
 * <p>当用户询问天气、气温、下雨、是否需要带伞、穿衣建议等天气相关问题时调用。</p>
 */
@Slf4j
@Component
public class WeatherTools {

    @Resource
    private IWeatherService weatherService;

    /**
     * 查询指定地点的实时天气情况。
     * <p>适用场景：用户询问"今天天气怎么样？""会下雨吗？""需要带伞吗？""温度多少？"等。</p>
     *
     * @param location 地点名称，如"西湖"、"雷峰塔"、"杭州"
     * @param lat      纬度坐标（可选），如果 RAG 上下文中提供了景点坐标则传入，提高查询精度
     * @param lon      经度坐标（可选），如果 RAG 上下文中提供了景点坐标则传入
     * @return 格式化天气文本，包含天气现象、温度、湿度、风速
     */
    @Tool(name = "checkWeather",
          description = "查询指定地点的实时天气情况。当用户询问天气、气温、温度、下雨、下雪、刮风、湿度、穿衣建议、需要带伞等天气相关问题时调用此工具。")
    public String checkWeather(
            @ToolParam(required = true,
                       description = "地点名称，如'西湖'、'雷峰塔'、'杭州'等")
            String location,
            @ToolParam(required = false,
                       description = "地点纬度坐标，如果上下文中提供了景点的坐标(x=经度, y=纬度)则传入景点坐标中的 y（纬度）值")
            Double lat,
            @ToolParam(required = false,
                       description = "地点经度坐标，如果上下文中提供了景点的坐标(x=经度, y=纬度)则传入景点坐标中的 x（经度）值")
            Double lon) {
        log.info("Tool 调用: checkWeather(location={}, lat={}, lon={})", location, lat, lon);
        String result = weatherService.getWeather(location, lat, lon);
        log.info("Tool 返回: {}", result);
        return result;
    }
}
