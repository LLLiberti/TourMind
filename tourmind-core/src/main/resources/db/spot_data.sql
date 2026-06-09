-- ============================================================
-- TourMind 旅游景点数据 — 杭州及周边
-- 替换原来的餐厅/KTV 数据，匹配旅游购票场景
-- 类型对应关系（来自 migration.sql）：
--   1:自然风光  2:主题乐园  3:文化古迹  4:户外运动
--   5:休闲度假  6:温泉SPA  7:亲子乐园  8:夜间游览
--   9:特色体验  10:网红打卡
-- 执行前建议先清空旧数据：DELETE FROM tb_spot WHERE id >= 15;
-- ============================================================

-- ==================== 自然风光（type_id=1） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (15, '西湖风景名胜区', 1, '/imgs/spots/xihu1.jpg,/imgs/spots/xihu2.jpg,/imgs/spots/xihu3.jpg', '西湖', '杭州市西湖区龙井路1号', 120.1412, 30.2377, 0, 0000015230, 49, '全天开放', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (16, '西溪国家湿地公园', 1, '/imgs/spots/xixi1.jpg,/imgs/spots/xixi2.jpg', '西溪', '杭州市西湖区天目山路518号', 120.0692, 30.2684, 80, 0000008920, 47, '08:00-17:30', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (17, '千岛湖风景区', 1, '/imgs/spots/qiandaohu1.jpg,/imgs/spots/qiandaohu2.jpg', '千岛湖', '杭州市淳安县千岛湖镇梦姑路348号', 119.0153, 29.6067, 130, 0000010340, 48, '08:00-17:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (18, '九溪烟树', 1, '/imgs/spots/jiuxi1.jpg,/imgs/spots/jiuxi2.jpg', '西湖', '杭州市西湖区九溪路', 120.1233, 30.2015, 0, 0000004560, 46, '全天开放', NOW(), NOW());

-- ==================== 主题乐园（type_id=2） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (19, '杭州乐园', 2, '/imgs/spots/hzleyuan1.jpg,/imgs/spots/hzleyuan2.jpg', '萧山', '杭州市萧山区风情大道2555号', 120.2436, 30.1744, 190, 0000012450, 45, '09:30-17:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (20, '杭州极地海洋公园', 2, '/imgs/spots/jidi1.jpg,/imgs/spots/jidi2.jpg', '萧山', '杭州市萧山区湘湖路777号', 120.2234, 30.1578, 260, 0000006780, 46, '09:00-17:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (21, '宋城景区', 2, '/imgs/spots/songcheng1.jpg,/imgs/spots/songcheng2.jpg', '之江', '杭州市西湖区之江路148号', 120.0909, 30.1744, 310, 0000015230, 47, '09:00-21:00', NOW(), NOW());

-- ==================== 文化古迹（type_id=3） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (22, '灵隐寺', 3, '/imgs/spots/lingyin1.jpg,/imgs/spots/lingyin2.jpg', '西湖', '杭州市西湖区法云弄1号', 120.1006, 30.2431, 75, 0000018050, 49, '07:00-18:15', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (23, '雷峰塔', 3, '/imgs/spots/leifengta1.jpg,/imgs/spots/leifengta2.jpg', '西湖', '杭州市西湖区南山路15号', 120.1501, 30.2281, 40, 0000014670, 47, '08:00-20:30', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (24, '河坊街/南宋御街', 3, '/imgs/spots/hefangjie1.jpg,/imgs/spots/hefangjie2.jpg', '吴山', '杭州市上城区河坊街180号', 120.1698, 30.2413, 0, 0000012890, 45, '全天开放', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (25, '良渚古城遗址公园', 3, '/imgs/spots/liangzhu1.jpg,/imgs/spots/liangzhu2.jpg', '良渚', '杭州市余杭区瓶窑镇凤都路', 119.9807, 30.3943, 60, 0000007560, 48, '09:00-17:00', NOW(), NOW());

-- ==================== 户外运动（type_id=4） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (26, '莫干山风景名胜区', 4, '/imgs/spots/moganshan1.jpg,/imgs/spots/moganshan2.jpg', '莫干山', '湖州市德清县莫干山风景区', 119.8702, 30.6075, 100, 0000009340, 48, '08:00-17:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (27, '大明山风景区', 4, '/imgs/spots/damingshan1.jpg,/imgs/spots/damingshan2.jpg', '临安', '杭州市临安区清凉峰镇', 119.0740, 30.0756, 110, 0000004230, 46, '08:00-16:30', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (28, '龙井村徒步道', 4, '/imgs/spots/longjing1.jpg,/imgs/spots/longjing2.jpg', '西湖', '杭州市西湖区龙井路', 120.1205, 30.2234, 0, 0000003120, 45, '全天开放', NOW(), NOW());

-- ==================== 休闲度假（type_id=5） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (29, '梅家坞茶文化村', 5, '/imgs/spots/meijiawu1.jpg,/imgs/spots/meijiawu2.jpg', '西湖', '杭州市西湖区梅灵南路梅家坞村', 120.1062, 30.2019, 0, 0000003890, 46, '全天开放', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (30, '桐庐富春江慢生活区', 5, '/imgs/spots/fuchunjiang1.jpg,/imgs/spots/fuchunjiang2.jpg', '桐庐', '杭州市桐庐县富春江镇', 119.6548, 29.7508, 80, 0000002670, 47, '08:00-17:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (31, '安吉云上草原', 5, '/imgs/spots/yunshang1.jpg,/imgs/spots/yunshang2.jpg', '安吉', '湖州市安吉县山川乡', 119.6501, 30.4801, 240, 0000005670, 47, '08:30-17:00', NOW(), NOW());

-- ==================== 温泉SPA（type_id=6） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (32, '湍口温泉度假村', 6, '/imgs/spots/tuankou1.jpg,/imgs/spots/tuankou2.jpg', '临安', '杭州市临安区湍口镇湍源街', 119.1133, 30.0456, 198, 0000003890, 45, '10:00-22:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (33, '云曼温泉', 6, '/imgs/spots/yunman1.jpg,/imgs/spots/yunman2.jpg', '萧山', '杭州市萧山区风情大道2555号', 120.2412, 30.1728, 168, 0000002150, 44, '13:00-23:00', NOW(), NOW());

-- ==================== 亲子乐园（type_id=7） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (34, '杭州动物园', 7, '/imgs/spots/dongwuyuan1.jpg,/imgs/spots/dongwuyuan2.jpg', '西湖', '杭州市西湖区虎跑路40号', 120.1328, 30.2161, 20, 0000008920, 46, '07:00-17:30', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (35, '烂苹果乐园', 7, '/imgs/spots/lanpingguo1.jpg,/imgs/spots/lanpingguo2.jpg', '萧山', '杭州市萧山区风情大道2555号', 120.2418, 30.1735, 160, 0000005120, 44, '09:30-17:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (36, '杭州植物园', 7, '/imgs/spots/zhiwuyuan1.jpg,/imgs/spots/zhiwuyuan2.jpg', '西湖', '杭州市西湖区桃源岭1号', 120.1189, 30.2567, 10, 0000004560, 45, '07:00-17:30', NOW(), NOW());

-- ==================== 夜间游览（type_id=8） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (37, '西湖夜游（印象西湖）', 8, '/imgs/spots/yinxiangxihu1.jpg,/imgs/spots/yinxiangxihu2.jpg', '西湖', '杭州市西湖区北山路82号', 120.1447, 30.2512, 360, 0000007230, 49, '19:30-21:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (38, '钱江新城灯光秀', 8, '/imgs/spots/dengguangxiu1.jpg,/imgs/spots/dengguangxiu2.jpg', '钱江新城', '杭州市上城区之江路1078号', 120.2126, 30.2435, 0, 0000006340, 47, '19:00-21:30（周二/五/六）', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (39, '京杭大运河夜游', 8, '/imgs/spots/dayunhe1.jpg,/imgs/spots/dayunhe2.jpg', '拱宸桥', '杭州市拱墅区湖墅南路208号', 120.1486, 30.3186, 120, 0000003120, 45, '18:30-21:00', NOW(), NOW());

-- ==================== 特色体验（type_id=9） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (40, '中国茶叶博物馆（双峰馆）', 9, '/imgs/spots/chabowuguan1.jpg,/imgs/spots/chabowuguan2.jpg', '西湖', '杭州市西湖区龙井路88号', 120.1246, 30.2327, 0, 0000003450, 47, '09:00-16:30（周一闭馆）', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (41, '手工艺活态馆（运河）', 9, '/imgs/spots/shougongyi1.jpg,/imgs/spots/shougongyi2.jpg', '拱宸桥', '杭州市拱墅区小河路450号', 120.1342, 30.3245, 30, 0000001980, 44, '09:00-17:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (42, '龙井茶园采茶体验', 9, '/imgs/spots/caicha1.jpg,/imgs/spots/caicha2.jpg', '西湖', '杭州市西湖区龙井路龙井村', 120.1202, 30.2228, 68, 0000002340, 46, '08:00-17:00（需预约）', NOW(), NOW());

-- ==================== 网红打卡（type_id=10） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (43, '法喜寺（上天竺）', 10, '/imgs/spots/faxisi1.jpg,/imgs/spots/faxisi2.jpg', '西湖', '杭州市西湖区天竺路338号', 120.1013, 30.2288, 10, 0000008670, 48, '04:00-19:00', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (44, '西湖断桥', 10, '/imgs/spots/duanqiao1.jpg,/imgs/spots/duanqiao2.jpg', '西湖', '杭州市西湖区北山路断桥', 120.1468, 30.2574, 0, 0000013560, 48, '全天开放', NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES (45, '满觉陇汤屋', 10, '/imgs/spots/manjuelong1.jpg,/imgs/spots/manjuelong2.jpg', '西湖', '杭州市西湖区满觉陇路75号', 120.1302, 30.2156, 0, 0000004230, 45, '全天开放（外部参观）', NOW(), NOW());

-- ============================================================
-- 验证数据
-- SELECT id, name, type_id, area, ticket_price, score FROM tb_spot WHERE id >= 15 ORDER BY type_id, id;
-- SELECT COUNT(*) AS spot_count FROM tb_spot;
-- ============================================================
