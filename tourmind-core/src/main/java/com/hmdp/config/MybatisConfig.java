package com.hmdp.config;

import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 * 注意：3.5.9 版本移除了 PaginationInnerInterceptor，基本分页功能通过 Page 类直接实现
 */
@Configuration
public class MybatisConfig {
    // MyBatis-Plus 3.5.9 版本已移除 PaginationInnerInterceptor
    // 分页功能通过 IService.page(IPage) 方法直接使用，无需额外配置
}
