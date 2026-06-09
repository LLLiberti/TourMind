-- ============================================================
-- TourMind 旅游购票系统 - 数据库迁移 SQL
-- 将原点评系统表结构迁移为旅游购票系统
-- 执行前请务必备份数据库！
-- ============================================================

-- 1. 重命名景点表（原商铺表）
RENAME TABLE `tb_shop` TO `tb_spot`;

-- 2. 重命名景点类型表（原商铺类型表）
RENAME TABLE `tb_shop_type` TO `tb_spot_type`;

-- 3. 修改博客表的景点关联字段
ALTER TABLE `tb_blog` CHANGE COLUMN `shop_id` `spot_id` BIGINT(20) NOT NULL COMMENT '景点id';

-- 4. 修改优惠券表的景点关联字段
ALTER TABLE `tb_voucher` CHANGE COLUMN `shop_id` `spot_id` BIGINT(20) UNSIGNED NULL DEFAULT NULL COMMENT '景点id';

-- 5. 景点表：重命名 avg_price → ticket_price，删除 sold 列
ALTER TABLE `tb_spot`
    CHANGE COLUMN `avg_price` `ticket_price` BIGINT(10) UNSIGNED NULL DEFAULT NULL COMMENT '门票价格，取整数（分）',
    DROP COLUMN `sold`;

-- 6. 更新景点表的字段注释
ALTER TABLE `tb_spot`
    MODIFY COLUMN `name` VARCHAR(128) NOT NULL COMMENT '景点名称',
    MODIFY COLUMN `type_id` BIGINT(20) UNSIGNED NOT NULL COMMENT '景点类型的id',
    MODIFY COLUMN `images` VARCHAR(1024) NOT NULL COMMENT '景点图片',
    MODIFY COLUMN `area` VARCHAR(128) NULL DEFAULT NULL COMMENT '景区，例如西湖',
    MODIFY COLUMN `address` VARCHAR(255) NOT NULL COMMENT '地址',
    MODIFY COLUMN `open_hours` VARCHAR(32) NULL DEFAULT NULL COMMENT '开放时间，例如 08:00-18:00';

-- 7. 更新景点类型表的字段注释
ALTER TABLE `tb_spot_type`
    MODIFY COLUMN `name` VARCHAR(32) NULL DEFAULT NULL COMMENT '景点类型名称';

-- 8. 更新博客表的字段注释
ALTER TABLE `tb_blog`
    MODIFY COLUMN `title` VARCHAR(255) NOT NULL COMMENT '游记标题',
    MODIFY COLUMN `images` VARCHAR(2048) NOT NULL COMMENT '游记照片，最多9张',
    MODIFY COLUMN `content` VARCHAR(2048) NOT NULL COMMENT '游记文字描述';

# 9. 更新景点类型数据（可选：根据旅游购票场景调整）
UPDATE `tb_spot_type` SET `name` = '自然风光' WHERE `id` = 1;
UPDATE `tb_spot_type` SET `name` = '主题乐园' WHERE `id` = 2;
UPDATE `tb_spot_type` SET `name` = '文化古迹' WHERE `id` = 3;
UPDATE `tb_spot_type` SET `name` = '户外运动' WHERE `id` = 4;
UPDATE `tb_spot_type` SET `name` = '休闲度假' WHERE `id` = 5;
UPDATE `tb_spot_type` SET `name` = '温泉SPA' WHERE `id` = 6;
UPDATE `tb_spot_type` SET `name` = '亲子乐园' WHERE `id` = 7;
UPDATE `tb_spot_type` SET `name` = '夜间游览' WHERE `id` = 8;
UPDATE `tb_spot_type` SET `name` = '特色体验' WHERE `id` = 9;
UPDATE `tb_spot_type` SET `name` = '网红打卡' WHERE `id` = 10;

-- ============================================================
-- 验证迁移结果
-- ============================================================
-- SHOW TABLES LIKE 'tb_spot%';
-- DESC tb_spot;
-- DESC tb_spot_type;
-- DESC tb_blog;
-- DESC tb_voucher;
