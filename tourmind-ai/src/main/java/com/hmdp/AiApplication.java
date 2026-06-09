package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tourmind-ai 模块的 Spring Boot 配置入口。
 *
 * <p>本模块不包含可执行的 main 方法；该类仅作为 {@code @SpringBootConfiguration}
 * 供本模块单元测试中的 {@code @SpringBootTest} 自动发现。</p>
 */
@SpringBootApplication
@MapperScan("com.hmdp.mapper")
public class AiApplication {
}
