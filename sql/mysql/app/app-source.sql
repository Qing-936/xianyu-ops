-- ============================================================================
-- 货源管理模块 DDL
-- 模块：xianyuops-module-app
-- 表前缀：app_source_
-- 说明：用户创建任务 → Java 发送 task_id 给 Python → Python 执行数据采集分析
--       → Python 填充搜索结果表 & 商品详情表 & 批次汇总表
-- 表清单：app_source_task / execution / item / item_detail / inventory / inventory_product
--           / clean_task / clean_skill / blocked_seller / cookie（2026-10-20 由 source-cookie-pool.sql 并入）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 货源采集任务表
-- 用户在前端创建任务（填写关键词/价格/获取条数/执行方式）
-- Java 保存后将 task_id 发给 Python 执行
-- 变更说明：min_price/max_price 重命名为 filter_min_price/filter_max_price，
--           语义更清晰（表示用户设定的筛选条件，区别于批次实际最低/最高价）
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS `app_source_task`;
CREATE TABLE `app_source_task` (
  `id`               BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_name`     VARCHAR(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '搜索关键词（商品名称）',
  `filter_min_price` INT DEFAULT NULL COMMENT '筛选最低价（分，用户设定的过滤条件）',
  `filter_max_price` INT DEFAULT NULL COMMENT '筛选最高价（分，用户设定的过滤条件）',
  `filter_view_count_min` INT DEFAULT NULL COMMENT '筛选最小浏览量',
  `filter_view_count_max` INT DEFAULT NULL COMMENT '筛选最大浏览量',
  `filter_want_count_min` INT DEFAULT NULL COMMENT '筛选最小想要量',
  `filter_want_count_max` INT DEFAULT NULL COMMENT '筛选最大想要量',
  `fetch_count`      INT NOT NULL DEFAULT 5 COMMENT '每次获取条数',
  `executed_count`   INT NOT NULL DEFAULT 0 COMMENT '已执行次数',
  `status`           TINYINT NOT NULL DEFAULT 0 COMMENT '任务状态（0=运行中 1=已暂停 2=已完成 3=提交失败 4=失败）',
  `error_message`    VARCHAR(512) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '提交失败原因（status=3时有值）',
  `trigger_mode`     TINYINT NOT NULL DEFAULT 0 COMMENT '执行方式（0=一次性 1=每日定时）',
  `schedule_time`    VARCHAR(8) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '每日执行时间（HH:mm格式，trigger_mode=1时有值）',
  `last_executed_at` DATETIME DEFAULT NULL COMMENT '最近一次执行时间',
  `last_viewed_execution_no` INT DEFAULT NULL COMMENT '最后查看的批次号（用于工作台未查看提醒，全局共享）',
  `creator`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`        BIGINT NOT NULL DEFAULT '0' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货源采集任务表';

-- 存量库执行（新增工作台未查看提醒字段）
ALTER TABLE `app_source_task`
    ADD COLUMN `last_viewed_execution_no` INT DEFAULT NULL COMMENT '最后查看的批次号（用于工作台未查看提醒，全局共享）' AFTER `last_executed_at`;

-- ----------------------------------------------------------------------------
-- 2. 货源采集批次汇总表
-- Python 每次执行完成后写入一条汇总记录
-- 对应原型 FetchResult Summary 卡片中的：关键词/获取条数/有效数/最低价/最高价/平均价/价格指数
-- app_source_item 通过 (task_id, execution_no) 与本表关联
-- 变更说明：新增 price_index（价格指数）
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS `app_source_execution`;
CREATE TABLE `app_source_execution` (
  `id`           BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id`      BIGINT NOT NULL COMMENT '关联任务ID（app_source_task.id）',
  `execution_no` INT NOT NULL DEFAULT 1 COMMENT '批次号（第几次执行，从1开始递增）',
  `keyword`      VARCHAR(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '本次执行的搜索关键词',
  `fetch_count`  INT NOT NULL DEFAULT 0 COMMENT '本批次实际获取条数',
  `valid_count`  INT NOT NULL DEFAULT 0 COMMENT '本批次有效数据条数（清洗后）',
  `min_price`    INT DEFAULT NULL COMMENT '本批次实际最低售价（分）',
  `max_price`    INT DEFAULT NULL COMMENT '本批次实际最高售价（分）',
  `avg_price`    INT DEFAULT NULL COMMENT '本批次平均售价（分，可选）',
  `price_index`  INT DEFAULT NULL COMMENT '价格指数（价格段数量，用于市场分析）',
  `source_cookie_id` BIGINT DEFAULT NULL COMMENT '本批次使用的共享 Cookie ID',
  `executed_at`  DATETIME DEFAULT NULL COMMENT '本批次执行时间',
  `status`       TINYINT NOT NULL DEFAULT 0 COMMENT '批次状态（0=执行中 1=已完成 2=执行失败）',
  `error_msg`    VARCHAR(512) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '失败原因（status=2时有值）',
  `creator`      VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`      VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`    BIGINT NOT NULL DEFAULT '0' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_execution` (`task_id`, `execution_no`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_executed_at` (`executed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货源采集批次汇总表';

-- ----------------------------------------------------------------------------
-- 3. 货源搜索结果表
-- Python 执行搜索后将列表结果填入此表
-- 通过 (task_id, execution_no) 关联到 app_source_execution 批次汇总表
-- 变更说明：新增 view_count（浏览量）、seller_level（卖家级别）、
--           heat_level（热度）、high_value（高价值）、competitiveness（竞争力）
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS `app_source_item`;
CREATE TABLE `app_source_item` (
  `id`               BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id`          BIGINT NOT NULL COMMENT '关联任务ID',
  `execution_no`     INT NOT NULL DEFAULT 1 COMMENT '执行批次号（关联 app_source_execution.execution_no）',
  `external_item_id` VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '外部商品ID（平台侧）',
  `title`            text COLLATE utf8mb4_unicode_ci DEFAULT null COMMENT '商品标题（对应原型 product_name 展示字段）',
  `price`            INT DEFAULT 0 COMMENT '售价（分）',
  `original_price`   INT DEFAULT 0 COMMENT '原价/划线价（分）',
  `pic_url`          VARCHAR(1024) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '主图URL',
  `detail_url`       VARCHAR(1024) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '商品详情页链接',
  `location`         VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '发货地',
  `want_count`       INT DEFAULT 0 COMMENT '想要人数',
  `view_count`       INT DEFAULT 0 COMMENT '浏览量',
  `publish_time`     VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '发布时间（平台原始值）',
  `seller_name`      VARCHAR(128) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '卖家昵称',
  `seller_credit`    VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '信用等级（极好/优秀/良好）',
  `seller_level`     VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '卖家级别（优质/个人）',
  `seller_review_count` VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '店铺评价数',
  `seller_good_rate` VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '好评率',
  `item_type`        VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '商品类型',
  `price_drop_tag`   VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '降价标签',
  `ship_speed`       VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '发货时效',
  `heat_level`       VARCHAR(16) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '热度（高/中/低）',
  `high_value`       VARCHAR(16) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '高价值（高/中/低/-）',
  `competitiveness`  VARCHAR(16) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '竞争力（高/中/低）',
  `tag_list`         JSON COMMENT '标签列表（fishTags原始JSON）',
  `search_keyword`   VARCHAR(128) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '搜索关键词',
  `is_valid`         BIT(1) NOT NULL DEFAULT b'1' COMMENT '是否有效（数据清洗后标记）',
  `crawl_time`       DATETIME DEFAULT NULL COMMENT '爬取时间',
  `creator`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`        BIGINT NOT NULL DEFAULT '1' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_execution` (`task_id`, `execution_no`),
  KEY `idx_keyword` (`search_keyword`),
  KEY `idx_price` (`price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货源搜索结果表';

-- ----------------------------------------------------------------------------
-- 4. 货源商品详情表
-- Python 对搜索结果中的商品逐个获取详情后填入此表
-- 包含完整的卖家信息、浏览/销量统计等
-- 无变更
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS `app_source_item_detail`;
CREATE TABLE `app_source_item_detail` (
  `id`                   BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id`              BIGINT NOT NULL COMMENT '关联任务ID',
  `item_id`              BIGINT DEFAULT NULL COMMENT '关联搜索结果ID（app_source_item.id）',
  `external_item_id`     VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '外部商品ID（平台侧）',
  `title`                text COLLATE utf8mb4_unicode_ci DEFAULT null COMMENT '标题',
  `price`                INT DEFAULT 0 COMMENT '价格（分）',
  `original_price`       INT DEFAULT 0 COMMENT '原价（分）',
  `pic_url`              VARCHAR(1024) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '主图URL',
  `images`               JSON COMMENT '商品其余图片列表（不含主图）',
  `detail_url`           VARCHAR(1024) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '详情页URL',
  `description`          TEXT COLLATE utf8mb4_unicode_ci COMMENT '商品描述',
  `location`             VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '发货地',
  `view_count`           INT DEFAULT 0 COMMENT '浏览量',
  `want_count`           INT DEFAULT 0 COMMENT '想要人数',
  `sold_count`           INT DEFAULT 0 COMMENT '已售数量',
  `collect_count`        INT DEFAULT 0 COMMENT '收藏数',
  `quantity`             INT DEFAULT 0 COMMENT '库存',
  `transport_fee`        VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '运费',
  `item_status`          VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '商品状态',
  `publish_time`         VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '发布时间（平台原始值）',
  `category_name`        VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '分类名称',
  `cpv_labels`           JSON COMMENT '属性标签（JSON）',
  `tag_list`             JSON COMMENT '标签列表（JSON）',
  `item_type`            VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '商品类型',
  `is_free_shipping`     BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否包邮',
  `seller_id`            VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '卖家ID',
  `seller_name`          VARCHAR(128) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '卖家昵称',
  `seller_avatar`        VARCHAR(512) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '卖家头像',
  `seller_city`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '卖家城市',
  `seller_sold_count`    INT DEFAULT 0 COMMENT '卖家已售数',
  `seller_reg_days`      INT DEFAULT 0 COMMENT '注册天数',
  `seller_zhima_auth`    BIT(1) NOT NULL DEFAULT b'0' COMMENT '芝麻认证',
  `seller_zhima_level`   VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '芝麻等级',
  `seller_credit_tag`    VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '信用等级标签',
  `seller_reply_ratio`   VARCHAR(16) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '回复率',
  `seller_reply_interval` VARCHAR(32) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '回复速度',
  `seller_last_visit`    VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '最近访问',
  `seller_good_rate`     VARCHAR(16) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '好评率',
  `seller_remark_good`   INT DEFAULT 0 COMMENT '好评数',
  `seller_remark_bad`    INT DEFAULT 0 COMMENT '差评数',
  `search_keyword`       VARCHAR(128) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '搜索关键词',
  `crawl_time`           DATETIME DEFAULT NULL COMMENT '采集时间',
  `creator`              VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`              VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`              BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`            BIGINT NOT NULL DEFAULT '1' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_item_id` (`item_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_external_item` (`external_item_id`),
  KEY `idx_keyword` (`search_keyword`),
  KEY `idx_price` (`price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货源商品详情表';

-- ----------------------------------------------------------------------------
-- 5. 货源入库表（我的货源库）
-- 管理端审核通过的采集结果沉淀于此；Python 可直接写入采集字段，
-- 后续由 Java 维护审核与闲鱼上架状态。
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS `app_source_inventory`;
CREATE TABLE `app_source_inventory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` BIGINT DEFAULT NULL COMMENT '关联采集任务ID（app_source_task.id）',
  `source_item_id` BIGINT DEFAULT NULL COMMENT '关联搜索结果ID（app_source_item.id）',
  `source_detail_id` BIGINT DEFAULT NULL COMMENT '关联详情ID（app_source_item_detail.id）',
  `external_item_id` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '外部平台商品ID',
  `title` TEXT COLLATE utf8mb4_0900_ai_ci COMMENT '商品标题',
  `description` TEXT COLLATE utf8mb4_0900_ai_ci COMMENT '商品描述',
  `search_keyword` VARCHAR(128) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '搜索关键词',
  `price` BIGINT DEFAULT 0 COMMENT '售价（分）',
  `original_price` BIGINT DEFAULT 0 COMMENT '原价/划线价（分）',
  `suggested_price` BIGINT DEFAULT NULL COMMENT '建议售价（分）',
  `profit_margin` DECIMAL(10, 2) DEFAULT NULL COMMENT '利润率（%）',
  `item_condition` VARCHAR(32) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '成色（全新/99新/95新等）',
  `function_status` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '功能状态（功能完好/屏幕有划痕等）',
  `brand` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '品牌',
  `pic_url` VARCHAR(1024) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '主图URL',
  `detail_url` VARCHAR(1024) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '详情页URL',
  `images` JSON COMMENT '商品图片列表（多图，用于上架时使用）',
  `stock` INT DEFAULT 1 COMMENT '库存数量',
  `location` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '发货地（原商品）',
  `province` VARCHAR(32) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '发货省份',
  `city` VARCHAR(32) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '发货城市',
  `transport_fee` VARCHAR(32) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '运费',
  `view_count` INT DEFAULT 0 COMMENT '原商品浏览量',
  `want_count` INT DEFAULT 0 COMMENT '原商品想要人数',
  `sold_count` INT DEFAULT 0 COMMENT '原商品已售数量',
  `seller_name` VARCHAR(128) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '原卖家昵称',
  `seller_city` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '原卖家城市',
  `seller_credit` VARCHAR(32) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '原卖家信用等级',
  `seller_level` VARCHAR(32) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '原卖家级别',
  `category_name` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '分类名称',
  `item_type` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '商品类型',
  `tag_list` JSON COMMENT '标签列表',
  `inventory_status` TINYINT NOT NULL DEFAULT 0 COMMENT '入库状态（0=待审核 1=已入库 2=已创建闲鱼商品 3=已上架 4=已售出 5=已下架）',
  `inventory_time` DATETIME DEFAULT NULL COMMENT '入库时间',
  `inventory_user` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '入库操作人',
  `xianyu_product_id` BIGINT DEFAULT NULL COMMENT '历史字段：关联商品 ID（开源核心版不再使用）',
  `xianyu_account_id` BIGINT DEFAULT NULL COMMENT '上架的闲鱼账号ID',
  `xianyu_remote_id` BIGINT DEFAULT NULL COMMENT '闲管家远端商品ID',
  `xianyu_status` TINYINT DEFAULT 0 COMMENT '闲鱼状态（0=未上架 1=待发布 2=销售中 3=已售出 4=已下架）',
  `remark` VARCHAR(500) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '备注（用户可添加备注）',
  `audit_remark` VARCHAR(255) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '审核备注',
  -- AI 清洗相关字段
  `cleaned_title` VARCHAR(512) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '清洗后的标题',
  `cleaned_description` TEXT COLLATE utf8mb4_0900_ai_ci COMMENT '清洗后的描述',
  `cleaned_images` TEXT COLLATE utf8mb4_0900_ai_ci COMMENT '清洗后的图片（JSON数组）',
  `clean_status` TINYINT DEFAULT 0 COMMENT '清洗状态：0未清洗/1清洗中/2已完成/3失败',
  `clean_time` DATETIME DEFAULT NULL COMMENT '清洗完成时间',
  `clean_config` TEXT COLLATE utf8mb4_0900_ai_ci COMMENT '清洗配置（JSON：记录清洗选项）',
  `clean_html` MEDIUMTEXT COLLATE utf8mb4_0900_ai_ci COMMENT '清洗工作流输出的HTML源码（用于后续微调）',
  `creator` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` VARCHAR(64) COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` BIGINT NOT NULL DEFAULT '1' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_source_item` (`source_item_id`),
  UNIQUE KEY `uk_source_detail_id` (`source_detail_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_external_item` (`tenant_id`, `external_item_id`, `deleted`),
  KEY `idx_inventory_status` (`inventory_status`),
  KEY `idx_xianyu_product` (`xianyu_product_id`),
  KEY `idx_keyword` (`search_keyword`),
  KEY `idx_inventory_time` (`inventory_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='货源入库表（我的货源库）';

-- ----------------------------------------------------------------------------
-- 货源 AI 清洗任务表
-- Java 负责入队与领取，后台 Worker 按租约串行执行 Dify 工作流
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `app_source_clean_task` (
  `id`               BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id`          BIGINT NOT NULL DEFAULT 0 COMMENT '提交任务的用户ID',
  `request_id`       VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '幂等请求号',
  `status`           TINYINT NOT NULL DEFAULT 0 COMMENT '任务状态（0=待处理 1=执行中 2=已完成 3=失败）',
  `total_count`      INT NOT NULL DEFAULT 0 COMMENT '货源总数',
  `completed_count`  INT NOT NULL DEFAULT 0 COMMENT '成功处理数',
  `failed_count`     INT NOT NULL DEFAULT 0 COMMENT '失败处理数',
  `inputs`           LONGTEXT NOT NULL COMMENT '任务输入快照（JSON）',
  `results`          LONGTEXT NOT NULL COMMENT '逐项处理结果（JSON）',
  `error_message`    VARCHAR(1000) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '任务错误信息',
  `lease_until`      DATETIME DEFAULT NULL COMMENT '执行租约截止时间',
  `creator`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`        BIGINT NOT NULL DEFAULT '0' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_user_request` (`tenant_id`, `user_id`, `request_id`, `deleted`),
  KEY `idx_status_lease` (`status`, `lease_until`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_user` (`tenant_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货源 AI 清洗任务表';

-- ----------------------------------------------------------------------------
-- 货源 AI 清洗 Skill 表
-- 预置 Skill 首次使用时按租户写入，自定义 Skill 不被资源文件覆盖
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `app_source_clean_skill` (
  `id`            BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `skill_code`    VARCHAR(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Skill 编码',
  `skill_type`    VARCHAR(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Skill 类型（title/description/images）',
  `skill_name`    VARCHAR(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'Skill 名称',
  `version`       VARCHAR(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'Skill 版本',
  `config`        LONGTEXT NOT NULL COMMENT 'Skill 配置（JSON）',
  `customized`    BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否为租户自定义配置',
  `enabled`       BIT(1) NOT NULL DEFAULT b'1' COMMENT '是否启用',
  `creator`       VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`       VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`     BIGINT NOT NULL DEFAULT '0' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_skill` (`tenant_id`, `skill_code`, `deleted`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_skill_type` (`tenant_id`, `skill_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货源 AI 清洗 Skill 表';

-- ----------------------------------------------------------------------------
-- 6. 货源拉黑卖家表
-- 拉黑来源：采集结果弹窗「拉黑卖家」（带来源任务）、拉黑卖家管理页手动添加
-- 匹配键：seller_id 优先、seller_name 兜底
--   - 从采集结果拉黑时优先取 app_source_item_detail.seller_id
--   - 取不到 seller_id 时（如仅列表数据）退化为按 seller_name 匹配
-- 生效点：Java 侧「采集结果列表展示」与「入库」动作排除黑名单卖家
-- 唯一性：生效中（status=1）的 seller_id / seller_name 由服务层校验
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS `app_source_blocked_seller`;
CREATE TABLE `app_source_blocked_seller` (
  `id`               BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `seller_id`        VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '闲鱼卖家ID（主匹配键，可空）',
  `seller_name`      VARCHAR(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '卖家名称/昵称（辅助匹配键 + 展示）',
  `block_reason`     VARCHAR(500) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '拉黑原因',
  `source_task_id`   BIGINT DEFAULT NULL COMMENT '来源任务ID（app_source_task.id，手动添加时为空）',
  `status`           TINYINT NOT NULL DEFAULT 1 COMMENT '状态（0=已解除 1=生效中）',
  `block_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '拉黑时间',
  `unblock_time`     DATETIME DEFAULT NULL COMMENT '解除时间（status=0时有值）',
  `creator`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`          VARCHAR(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`        BIGINT NOT NULL DEFAULT '0' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_status` (`tenant_id`, `status`),
  KEY `idx_seller_id` (`seller_id`),
  KEY `idx_seller_name` (`seller_name`),
  KEY `idx_source_task` (`source_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货源拉黑卖家表';

-- ----------------------------------------------------------------------------
-- 7. 货源采集共享 Cookie 池（原 source-cookie-pool.sql）
--    多份 Cookie 并存、同 unb 覆盖刷新、运行中自动回写 _m_h5_tk / _m_h5_tk_enc
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS `app_source_cookie`;
CREATE TABLE `app_source_cookie` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `unb` VARCHAR(128) NOT NULL COMMENT '闲鱼账号唯一标识，用于同账号覆盖刷新',
  `cookie_json` TEXT NOT NULL COMMENT 'Cookie JSON 原文（当前最小实现先不做加密）',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0可用 1冷却中 2已失效 3已停用',
  `last_used_at` DATETIME DEFAULT NULL COMMENT '最近一次被采集使用时间',
  `last_refresh_at` DATETIME DEFAULT NULL COMMENT '最近一次运行中自动回写时间',
  `cooldown_until` DATETIME DEFAULT NULL COMMENT '冷却截止时间',
  `creator` VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID；共享池固定为 0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_cookie_unb` (`unb`),
  KEY `idx_source_cookie_status_cooldown` (`status`, `cooldown_until`),
  KEY `idx_source_cookie_refresh_used` (`last_refresh_at`, `last_used_at`),
  KEY `idx_source_cookie_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='货源采集共享 Cookie 池';

COMMIT;

