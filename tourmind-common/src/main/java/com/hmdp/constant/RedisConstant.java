package com.hmdp.constant;

/**
 * Redis 键常量 — 按 {@code <domain>:<subdomain>:} 模式命名。
 *
 * <p>本类位于 tourmind-common，供所有模块共享。</p>
 *
 * @author lbq
 */
public class RedisConstant {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SPOT_TTL = 30L;
    public static final String CACHE_SPOT_KEY = "cache:spot:";
    public static final String CACHE_SPOT_TYPE_KEY = "cache:spot:type:";

    public static final String LOCK_SPOT_KEY = "lock:spot:";
    public static final Long LOCK_SPOT_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SPOT_GEO_KEY = "spot:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
