-- ============================================================
-- TourMind 旅游景点数据 — 杭州及周边（v2，含特色、购票须知、退票条件）
-- 替换原来的餐厅/KTV 数据，匹配旅游购票场景
-- 类型对应关系（来自 migration.sql）：
--   1:自然风光  2:主题乐园  3:文化古迹  4:户外运动
--   5:休闲度假  6:温泉SPA  7:亲子乐园  8:夜间游览
--   9:特色体验  10:网红打卡
-- 执行前建议先清空旧数据：DELETE FROM tb_spot WHERE id >= 15;
-- ============================================================

-- ==================== 自然风光（type_id=1） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (15, '西湖风景名胜区', 1, '/imgs/spots/xihu1.jpg,/imgs/spots/xihu2.jpg,/imgs/spots/xihu3.jpg', '西湖', '杭州市西湖区龙井路1号', 120.1412, 30.2377, 0, 0000015230, 49, '全天开放',
'湖光山色冠绝天下，四季景致各有千秋。春有苏堤春晓，夏有曲院风荷，秋有平湖秋月，冬有断桥残雪。环湖有白堤、苏堤、杨公堤三大长堤，周边散落雷峰塔、灵隐寺、龙井村等众多名胜，是杭州最核心的城市名片。',
'西湖风景区免费开放，无需购票。部分景点（如三潭印月、雷峰塔等）需单独购票。游船需在码头现场购票或通过官方小程序预订。',
'西湖本身免费，不涉及退票。游船票未使用可在发船前通过原购买渠道退款，发船后不可退。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (16, '西溪国家湿地公园', 1, '/imgs/spots/xixi1.jpg,/imgs/spots/xixi2.jpg', '西溪', '杭州市西湖区天目山路518号', 120.0692, 30.2684, 80, 0000008920, 47, '08:00-17:30',
'城市中的湿地秘境，水道纵横、芦苇摇曳、白鹭翩飞。可乘摇橹船穿行于芦苇荡间，感受远离喧嚣的原生态自然之美，是城市生态保护的典范。',
'门票80元/人，建议提前1天在官方微信公众号或旅游平台预约购票。入园需出示二维码或身份证。学生、老人凭有效证件享半价优惠。',
'未使用的门票在游玩日期前可全额退款，游玩当日不可退票。如遇恶劣天气导致闭园，可于3个工作日内申请全额退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (17, '千岛湖风景区', 1, '/imgs/spots/qiandaohu1.jpg,/imgs/spots/qiandaohu2.jpg', '千岛湖', '杭州市淳安县千岛湖镇梦姑路348号', 119.0153, 29.6067, 130, 0000010340, 48, '08:00-17:00',
'以碧水千岛闻名于世，湖区面积广阔，水质达国家一级标准。可乘船游览梅峰岛、龙山岛等核心岛屿，登高俯瞰千岛竞秀的壮丽画卷。适合游船、垂钓、骑行等多种休闲方式。',
'门票130元/人（含游船票），需凭身份证实名购票。可在官网、微信公众号或现场售票窗口购买。游船班次每小时一班，请合理规划时间。',
'未使用的门票在游玩日期前可全额退款。发船前2小时以上可退船票，发船后船票不可退。如因天气原因停航，可全额退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (18, '九溪烟树', 1, '/imgs/spots/jiuxi1.jpg,/imgs/spots/jiuxi2.jpg', '西湖', '杭州市西湖区九溪路', 120.1233, 30.2015, 0, 0000004560, 46, '全天开放',
'清幽溪流沿山势蜿蜒而下，溪水潺潺、茶园叠翠。秋季枫叶红遍山野，与龙井茶园交织成画，是杭州最美的徒步摄影路线之一，尤以"九溪十八涧"最为知名。',
'免费开放，无需购票。建议穿舒适的徒步鞋，全程约3-5公里。春秋季节游人较多，建议错峰出行。',
'免费景点，不涉及退票。',
NOW(), NOW());

-- ==================== 主题乐园（type_id=2） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (19, '杭州乐园', 2, '/imgs/spots/hzleyuan1.jpg,/imgs/spots/hzleyuan2.jpg', '萧山', '杭州市萧山区风情大道2555号', 120.2436, 30.1744, 190, 0000012450, 45, '09:30-17:00',
'杭州大型综合性主题乐园，拥有过山车、大摆锤等刺激项目及亲子互动专区。定期举办万圣节、圣诞节等主题庆典活动，适合家庭和年轻人群。',
'门票190元/人，可在线购票扫码入园。儿童身高1.2米以下免票，1.2-1.5米享儿童票。部分项目有身高限制，请入园前查看公告。',
'未使用的门票在购买后7天内可申请退款。已入园或超过7天不予退款。如遇设备检修闭园，可全额退款或改签。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (20, '杭州极地海洋公园', 2, '/imgs/spots/jidi1.jpg,/imgs/spots/jidi2.jpg', '萧山', '杭州市萧山区湘湖路777号', 120.2234, 30.1578, 260, 0000006780, 46, '09:00-17:00',
'集极地动物展示与海洋生物科普于一体。明星项目包括企鹅馆、北极熊馆、白鲸表演和360度海底隧道，是亲子家庭的热门之选。',
'门票260元/人，提前1天在官方平台购票享9折优惠。表演场次固定，建议入园后先查看当日表演时间表。儿童1米以下免票。',
'未使用的门票在购买后3天内可申请退款，过期不予退款。已入园后不可退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (21, '宋城景区', 2, '/imgs/spots/songcheng1.jpg,/imgs/spots/songcheng2.jpg', '之江', '杭州市西湖区之江路148号', 120.0909, 30.1744, 310, 0000015230, 47, '09:00-21:00',
'以宋代文化为主题的大型沉浸式景区，核心项目《宋城千古情》被誉为"世界三大名秀"之一。景区内有仿宋街市、民俗表演、互动体验等，一步一景梦回千年。',
'门票310元/人（含《宋城千古情》演出），购票时需选定演出场次。建议提前1-2天购票，节假日场次紧张。凭二维码或身份证入场。',
'演出前24小时以上可全额退款，演出前24小时内退票收取20%手续费，演出开始后不可退款。',
NOW(), NOW());

-- ==================== 文化古迹（type_id=3） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (22, '灵隐寺', 3, '/imgs/spots/lingyin1.jpg,/imgs/spots/lingyin2.jpg', '西湖', '杭州市西湖区法云弄1号', 120.1006, 30.2431, 75, 0000018050, 49, '07:00-18:15',
'千年古刹，江南禅宗名寺。寺内有大雄宝殿、天王殿、药师殿等宏伟建筑，飞来峰摩崖石刻为全国重点文物保护单位。香火鼎盛，是杭州祈福礼佛的首选之地。',
'门票75元/人（含飞来峰景区门票，不含灵隐寺香花券30元）。香花券需入寺后另购。可通过"杭州灵隐寺"微信公众号或现场购票。',
'未使用的门票在游玩日期前可全额退款，游玩当日不予退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (23, '雷峰塔', 3, '/imgs/spots/leifengta1.jpg,/imgs/spots/leifengta2.jpg', '西湖', '杭州市西湖区南山路15号', 120.1501, 30.2281, 40, 0000014670, 47, '08:00-20:30',
'西湖十景"雷峰夕照"所在地，登塔可360度俯瞰西湖全景。塔内展示雷峰塔地宫出土文物和白蛇传文化主题展览，集自然风光与人文传说于一体。',
'门票40元/人，凭身份证实名购票入园。可现场购票或通过旅游平台预订。学生、老人凭有效证件享半价优惠。',
'未使用的门票在购买后7天内可申请退款。已入园后不可退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (24, '河坊街/南宋御街', 3, '/imgs/spots/hefangjie1.jpg,/imgs/spots/hefangjie2.jpg', '吴山', '杭州市上城区河坊街180号', 120.1698, 30.2413, 0, 0000012890, 45, '全天开放',
'杭州最具市井气息的历史街区，南宋御街与河坊街相连，青石板路两侧林立老字号、特色小吃、手工艺品店。可品尝定胜糕、葱包桧等地道杭帮小吃，感受老杭州的烟火气。',
'免费开放的步行街区，无需购票。部分展馆和体验项目需单独购票。',
'免费景区，不涉及退票。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (25, '良渚古城遗址公园', 3, '/imgs/spots/liangzhu1.jpg,/imgs/spots/liangzhu2.jpg', '良渚', '杭州市余杭区瓶窑镇凤都路', 119.9807, 30.3943, 60, 0000007560, 48, '09:00-17:00',
'世界文化遗产，实证中华五千年文明史的圣地。遗址公园保留了古城墙、宫殿区、反山王陵等遗迹，通过展示馆和考古体验让人近距离感受良渚先民的智慧与文明。',
'门票60元/人，需提前在"良渚古城遗址公园"微信公众号实名预约，每日限流。入园时凭预约码和身份证核验。',
'参观前1天以上可免费取消预约。参观当日不可退款。如因不可抗力因素闭园，可全额退款。',
NOW(), NOW());

-- ==================== 户外运动（type_id=4） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (26, '莫干山风景名胜区', 4, '/imgs/spots/moganshan1.jpg,/imgs/spots/moganshan2.jpg', '莫干山', '湖州市德清县莫干山风景区', 119.8702, 30.6075, 100, 0000009340, 48, '08:00-17:00',
'中国四大避暑胜地之一，以竹海、清泉、云海、别墅群著称。山林覆盖率超90%，夏季气温比城市低6-8℃。可徒步剑池、登顶塔山、骑行裸心谷，是江浙沪最受欢迎的户外休闲目的地。',
'门票100元/人，可在官方微信公众号或现场购票。景区内有接驳车（另收费），也可自驾进入部分区域。建议穿着运动鞋。',
'未使用的门票在游玩日期前可全额退款，游玩当日不可退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (27, '大明山风景区', 4, '/imgs/spots/damingshan1.jpg,/imgs/spots/damingshan2.jpg', '临安', '杭州市临安区清凉峰镇', 119.0740, 30.0756, 110, 0000004230, 46, '08:00-16:30',
'以奇松、怪石、云海、飞瀑著称的浙西高山景区。有悬空栈道、高山草甸和滑雪场（冬季），四季各有特色。主峰海拔1489米，可乘缆车上山。',
'门票110元/人（不含缆车），缆车单程50元/人。可通过各旅游平台或现场购票。冬季滑雪需另行购买滑雪票。',
'未使用的门票在购买后7天内可退款。缆车票和滑雪票一经使用不可退款。如遇极端天气关闭景区，可全额退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (28, '龙井村徒步道', 4, '/imgs/spots/longjing1.jpg,/imgs/spots/longjing2.jpg', '西湖', '杭州市西湖区龙井路', 120.1205, 30.2234, 0, 0000003120, 45, '全天开放',
'杭州最经典的徒步路线之一，穿梭于龙井茶园与山林之间。沿途可闻茶香、听鸟鸣、观溪流，是都市人亲近自然的轻户外之选。全程约5公里，适合各年龄段徒步爱好者。',
'免费开放，无需购票。建议穿着舒适运动鞋，携带饮用水。茶园为私人种植区，请勿随意采摘茶叶。',
'免费景点，不涉及退票。',
NOW(), NOW());

-- ==================== 休闲度假（type_id=5） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (29, '梅家坞茶文化村', 5, '/imgs/spots/meijiawu1.jpg,/imgs/spots/meijiawu2.jpg', '西湖', '杭州市西湖区梅灵南路梅家坞村', 120.1062, 30.2019, 0, 0000003890, 46, '全天开放',
'西湖龙井茶核心产区，茶园连绵起伏、茶香四溢。可参观茶农制茶工艺、品尝明前龙井，在农家茶楼坐享一杯清茶，感受杭州慢生活的最高境界。',
'免费开放的茶文化村落，无需购票。品茶消费视各茶楼而定，建议先询价。春季采茶季可预约采茶体验（另收费）。',
'村落免费进入，不涉及退票。预约项目按各商户规定执行。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (30, '桐庐富春江慢生活区', 5, '/imgs/spots/fuchunjiang1.jpg,/imgs/spots/fuchunjiang2.jpg', '桐庐', '杭州市桐庐县富春江镇', 119.6548, 29.7508, 80, 0000002670, 47, '08:00-17:00',
'富春江畔的慢生活度假区，以元代画家黄公望《富春山居图》的实景地为背景。江景秀丽、村落古朴，可体验垂钓、骑行、民宿等慢节奏休闲活动。',
'门票80元/人。度假区内各景点和住宿需分别预订。建议提前预订民宿，节假日房源紧张。',
'未使用的门票在游玩日期前可退款。住宿退款按各民宿规定执行，请预订时确认。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (31, '安吉云上草原', 5, '/imgs/spots/yunshang1.jpg,/imgs/spots/yunshang2.jpg', '安吉', '湖州市安吉县山川乡', 119.6501, 30.4801, 240, 0000005670, 47, '08:30-17:00',
'海拔1168米的高山草原度假区，云海奇观频现。夏季可滑草、露营、观星，冬季可滑雪。悬崖秋千、玻璃栈道等网红项目极具挑战性。',
'门票240元/人（含索道），可通过官方平台或旅游App购票。部分极限项目需额外付费。山上气温较低，建议携带外套。',
'未使用的门票在游玩日期前可全额退款。索道票一经使用不可退款。如遇恶劣天气索道停运，可退索道费用。',
NOW(), NOW());

-- ==================== 温泉SPA（type_id=6） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (32, '湍口温泉度假村', 6, '/imgs/spots/tuankou1.jpg,/imgs/spots/tuankou2.jpg', '临安', '杭州市临安区湍口镇湍源街', 119.1133, 30.0456, 198, 0000003890, 45, '10:00-22:00',
'临安深山中的天然温泉度假村，温泉水富含多种矿物质。拥有室内外多个温泉池，环境清幽、竹林环绕，是冬季驱寒养生的理想去处。',
'温泉票198元/人，建议提前预约。需自备泳衣，浴巾和拖鞋由度假村提供。儿童1.2米以下免票。',
'未使用的温泉票在预约日期前可全额退款，预约当日不退。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (33, '云曼温泉', 6, '/imgs/spots/yunman1.jpg,/imgs/spots/yunman2.jpg', '萧山', '杭州市萧山区风情大道2555号', 120.2412, 30.1728, 168, 0000002150, 44, '13:00-23:00',
'城市中的日式温泉体验，位于杭州乐园附近。温泉水质优良，有玫瑰池、红酒池、中药池等多种主题泡池，并配备休息区和简餐服务。',
'温泉票168元/人，可通过官方平台购票。提供浴巾和拖鞋，建议自备泳衣。周末人流较大，建议工作日前往。',
'未使用的温泉票在购买后7天内可退款。已入园后不可退款。',
NOW(), NOW());

-- ==================== 亲子乐园（type_id=7） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (34, '杭州动物园', 7, '/imgs/spots/dongwuyuan1.jpg,/imgs/spots/dongwuyuan2.jpg', '西湖', '杭州市西湖区虎跑路40号', 120.1328, 30.2161, 20, 0000008920, 46, '07:00-17:30',
'杭州经典的亲子游览场所，拥有大熊猫、金丝猴、长颈鹿等上百种动物。园内绿树成荫，设有儿童游乐区和科普教育馆，是周末遛娃的好去处。',
'门票20元/人，性价比极高。现场购票即可，也可在各旅游平台提前购买。儿童1.2米以下免票，学生凭学生证半价。',
'未使用的门票在游玩日期前可退款。已入园后不可退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (35, '烂苹果乐园', 7, '/imgs/spots/lanpingguo1.jpg,/imgs/spots/lanpingguo2.jpg', '萧山', '杭州市萧山区风情大道2555号', 120.2418, 30.1735, 160, 0000005120, 44, '09:30-17:00',
'专为3-12岁儿童打造的室内亲子乐园，拥有魔法城堡、泡泡工厂、挖沙乐园等趣味项目。全室内设计不受天气影响，是雨天遛娃的首选。',
'门票160元/人（1成人+1儿童），额外成人需购陪同票40元/人。建议提前在官方平台购票，周末可能限流。',
'未使用的门票在购买后3天内可退款。已入园后不可退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (36, '杭州植物园', 7, '/imgs/spots/zhiwuyuan1.jpg,/imgs/spots/zhiwuyuan2.jpg', '西湖', '杭州市西湖区桃源岭1号', 120.1189, 30.2567, 10, 0000004560, 45, '07:00-17:30',
'集植物科研、观赏游览、科普教育于一体的综合性植物园。拥有木兰山茶园、竹类植物区、水生植物区等十余个专类园区，四季花卉不断，是摄影和自然教育的热门地。',
'门票10元/人，现场购票或通过旅游平台购买。园内部分温室和特展需额外购票。春季花展期间门票可能调整。',
'未使用的门票在游玩日期前可退款。已入园后不可退款。',
NOW(), NOW());

-- ==================== 夜间游览（type_id=8） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (37, '西湖夜游（印象西湖）', 8, '/imgs/spots/yinxiangxihu1.jpg,/imgs/spots/yinxiangxihu2.jpg', '西湖', '杭州市西湖区北山路82号', 120.1447, 30.2512, 360, 0000007230, 49, '19:30-21:00',
'由张艺谋团队打造的大型水上实景演出，以西湖山水为背景，演绎白蛇传等经典传说。声光电效果震撼，是杭州夜游的金字招牌。',
'门票360元/人，按座位区域定价。需提前在官方平台购票并选定日期和场次。演出为户外实景，如遇小雨照常演出，大雨取消。',
'演出前48小时以上可全额退款，48小时内退票收取30%手续费，演出开始后不可退款。如因天气原因取消演出，全额退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (38, '钱江新城灯光秀', 8, '/imgs/spots/dengguangxiu1.jpg,/imgs/spots/dengguangxiu2.jpg', '钱江新城', '杭州市上城区之江路1078号', 120.2126, 30.2435, 0, 0000006340, 47, '19:00-21:30（周二/五/六）',
'杭州最具现代感的夜景之一，钱塘江两岸摩天大楼联动演绎巨型灯光秀。最佳观赏点在城市阳台和钱江世纪公园，灯光变幻配合音乐，视觉冲击力极强。',
'免费开放的公共景观，无需购票。演出时间为每周二、五、六19:00-21:30。建议提前到达占好观赏位置。',
'免费项目，不涉及退票。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (39, '京杭大运河夜游', 8, '/imgs/spots/dayunhe1.jpg,/imgs/spots/dayunhe2.jpg', '拱宸桥', '杭州市拱墅区湖墅南路208号', 120.1486, 30.3186, 120, 0000003120, 45, '18:30-21:00',
'乘坐古色古香的画舫夜游京杭大运河，两岸古建筑在灯光映衬下别具韵味。途经拱宸桥、小河直街等历史街区，船上配有讲解和茶点，是了解杭州运河文化的独特方式。',
'船票120元/人，含茶点和讲解。需在码头售票处或旅游平台提前购票。每晚一班18:30发船，全程约1.5小时。',
'发船前2小时以上可全额退款，发船前2小时内退票收取50%手续费，发船后不可退款。',
NOW(), NOW());

-- ==================== 特色体验（type_id=9） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (40, '中国茶叶博物馆（双峰馆）', 9, '/imgs/spots/chabowuguan1.jpg,/imgs/spots/chabowuguan2.jpg', '西湖', '杭州市西湖区龙井路88号', 120.1246, 30.2327, 0, 0000003450, 47, '09:00-16:30（周一闭馆）',
'中国唯一的国家级茶文化专题博物馆，系统地展示中国茶史、茶器、茶艺。双峰馆区坐落于龙井茶园之中，环境优雅，可免费品鉴各类名茶，是了解中华茶文化的最佳场所。',
'免费开放，需在公众号提前预约。开放时间09:00-16:30，周一闭馆。馆内有茶艺表演（另收费，需预约）。',
'免费参观，不涉及退票。茶艺表演预约后不可退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (41, '手工艺活态馆（运河）', 9, '/imgs/spots/shougongyi1.jpg,/imgs/spots/shougongyi2.jpg', '拱宸桥', '杭州市拱墅区小河路450号', 120.1342, 30.3245, 30, 0000001980, 44, '09:00-17:00',
'位于运河畔的手工艺体验馆，汇聚竹编、扎染、剪纸、陶艺等传统手工艺项目。由非遗传承人现场教学，游客可亲手制作属于自己的手工艺品，体验感十足。',
'门票30元/人（参观票），体验项目另收费（每项20-80元不等）。建议提前电话预约体验项目，周末人气较高。',
'参观票购买后不可退款。体验项目预约后如需取消，提前1天可全额退款，当天不退。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (42, '龙井茶园采茶体验', 9, '/imgs/spots/caicha1.jpg,/imgs/spots/caicha2.jpg', '西湖', '杭州市西湖区龙井路龙井村', 120.1202, 30.2228, 68, 0000002340, 46, '08:00-17:00（需预约）',
'走进西湖龙井核心产区，在茶农指导下亲手采摘明前嫩芽，体验传统手工炒茶技艺。活动包括采茶、炒茶、品茶三个环节，完成后可带走自己炒制的茶叶，是深度体验龙井茶文化的绝佳方式。',
'采茶体验68元/人，需至少提前1天电话预约。体验时长约2小时，提供采茶工具。建议穿着长袖长裤，春季采茶最佳。',
'预约后如需取消，提前1天可全额退款。当日取消收取30%费用。如遇大雨无法进行，可改期或全额退款。',
NOW(), NOW());

-- ==================== 网红打卡（type_id=10） ====================

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (43, '法喜寺（上天竺）', 10, '/imgs/spots/faxisi1.jpg,/imgs/spots/faxisi2.jpg', '西湖', '杭州市西湖区天竺路338号', 120.1013, 30.2288, 10, 0000008670, 48, '04:00-19:00',
'杭州新晋网红寺庙，以黄色外墙和"好运"御守闻名。寺内建筑古朴精致，依山而建层次分明，是拍照打卡和祈福许愿的热门去处，尤其受年轻游客青睐。',
'门票10元/人，现场购票。寺院内求签和请御守需现金支付，建议备好零钱。清晨人少，适合静心游览。',
'门票一经售出不予退款。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (44, '西湖断桥', 10, '/imgs/spots/duanqiao1.jpg,/imgs/spots/duanqiao2.jpg', '西湖', '杭州市西湖区北山路断桥', 120.1468, 30.2574, 0, 0000013560, 48, '全天开放',
'西湖十景"断桥残雪"所在地，是白蛇传中许仙与白娘子相遇的浪漫之地。桥身优雅横跨湖面，远山近水尽收眼底。冬季雪后桥面若隐若现于湖中，最是经典画面。',
'免费开放的西湖景区组成部分，无需购票。全天可游览，日出日落时分光线最佳，适合拍照。',
'免费景点，不涉及退票。',
NOW(), NOW());

INSERT INTO `tb_spot` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `ticket_price`, `comments`, `score`, `open_hours`, `features`, `ticket_notice`, `refund_policy`, `create_time`, `update_time`)
VALUES (45, '满觉陇汤屋', 10, '/imgs/spots/manjuelong1.jpg,/imgs/spots/manjuelong2.jpg', '西湖', '杭州市西湖区满觉陇路75号', 120.1302, 30.2156, 0, 0000004230, 45, '全天开放（外部参观）',
'以一座形似《千与千寻》汤屋的独特建筑走红社交网络。秋季满陇桂花盛开，金黄桂花与古朴建筑相映成趣。适合拍照打卡，感受杭州小众文艺的一面。',
'免费开放（外部参观），无需购票。建筑内部不对外开放，请勿擅自进入。周边有桂花衍生品商店，可购买桂花糕、桂花蜜等特产。',
'免费景点，不涉及退票。',
NOW(), NOW());

-- ============================================================
-- 验证数据
-- SELECT id, name, type_id, area, ticket_price, score FROM tb_spot WHERE id >= 15 ORDER BY type_id, id;
-- SELECT COUNT(*) AS spot_count FROM tb_spot;
-- ============================================================
