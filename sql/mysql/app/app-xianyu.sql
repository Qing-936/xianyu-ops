-- ============================================================================
-- 闲鱼核心模块 — Agent / 消息 / 回复任务 DDL + 种子（6 张表）
-- 模块：xianyuops-module-xianyu
-- 表前缀：app_xianyu_
-- 覆盖范围：Agent 实例、账号绑定快照、买家消息、回复任务、运行事件、一次性注册码
--            + 菜单/定时任务种子
-- ⚠️ 本文件含 DROP TABLE，只能用于全新建库；存量库只做结构参考。
-- ============================================================================

-- ============================================================================
-- 1. 多账号消息管理（5 张表）— 原 app-xianyu-message.sql
-- ============================================================================

-- ① Agent 实例表
DROP TABLE IF EXISTS `app_xianyu_agent`;
CREATE TABLE `app_xianyu_agent` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'Agent 名称（默认取 host_name，管理端可改）',
  `machine_code` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '机器指纹（唯一，首次心跳注册）',
  `token_hash` VARCHAR(64) DEFAULT NULL COMMENT '专属 Token 的 SHA-256（NULL=未激活；M5 一机一 Token，明文仅注册/重置时下发一次）',
  `host_name` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '主机名',
  `os_version` VARCHAR(128) DEFAULT '' COMMENT '操作系统版本',
  `agent_version` VARCHAR(32) DEFAULT '' COMMENT 'Agent 程序版本',
  `capabilities` VARCHAR(128) NOT NULL DEFAULT 'text' COMMENT '能力声明：text,image,emoji（旧 Agent 仅 text）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'offline' COMMENT '状态：online/offline',
  `screen_locked` BIT(1) NOT NULL DEFAULT b'0' COMMENT '最近心跳是否锁屏（1=锁屏，管理端告警）',
  `last_heartbeat_at` DATETIME DEFAULT NULL COMMENT '最后心跳时间',
  `remark` VARCHAR(255) DEFAULT '' COMMENT '备注',
  `creator` VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_status` (`status`),
  UNIQUE KEY `uk_machine_code` (`tenant_id`, `machine_code`, `deleted`),
  UNIQUE KEY `uk_token_hash` (`tenant_id`, `token_hash`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闲鱼 Agent 实例';

-- ② Agent × 闲鱼号绑定表
DROP TABLE IF EXISTS `app_xianyu_agent_account`;
CREATE TABLE `app_xianyu_agent_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` BIGINT NOT NULL COMMENT 'Agent ID',
  `account_id` BIGINT DEFAULT NULL COMMENT '闲鱼账号 ID（开源核心版固定为 NULL，仅保留字段兼容）',
  `authorize_id` VARCHAR(64) DEFAULT NULL COMMENT '授权 ID 快照（心跳上报，可空）',
  `user_nick` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '闲鱼号昵称快照',
  `login_status` VARCHAR(16) NOT NULL DEFAULT 'unknown' COMMENT '登录状态：online/offline/unknown',
  `last_report_at` DATETIME DEFAULT NULL COMMENT '最近一次上报时间',
  `creator` VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_account` (`account_id`),
  UNIQUE KEY `uk_agent_account` (`agent_id`, `account_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闲鱼 Agent 账号绑定（运行态，account_id 未对齐可为 NULL）';

-- ③ 买家消息表
DROP TABLE IF EXISTS `app_xianyu_chat_message`;
CREATE TABLE `app_xianyu_chat_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` BIGINT NOT NULL COMMENT '来源 Agent',
  `account_id` BIGINT DEFAULT NULL COMMENT '闲鱼账号 ID（对齐失败可空）',
  `account_nick` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '账号昵称快照（accountKey.userNick；未对齐会话的聚合兜底键与判重维度，评审 P1）',
  `buyer_id` VARCHAR(64) DEFAULT NULL COMMENT '买家 ID（OCR 不一定拿得到，可空）',
  `buyer_nick` VARCHAR(128) NOT NULL COMMENT '买家昵称（会话键，必填）',
  `chat_id` VARCHAR(64) DEFAULT NULL COMMENT '会话唯一标识（= 会话行 sessionInfo.sessionId，Agent 扫码阶段直取；非空时作为会话聚合主键，替代 buyer_nick 兜底）',
  `item_id` BIGINT DEFAULT NULL COMMENT '会话所属闲鱼商品 ID（闲鱼 item id，可空=未关联商品会话；优先取会话行 sessionInfo.itemInfo.itemId，商品卡推断为兜底）',
  `direction` VARCHAR(8) NOT NULL DEFAULT 'in' COMMENT '方向：in（收到）/ out（发出，任务成功时 Java 补录）',
  `content_type` VARCHAR(16) NOT NULL DEFAULT 'text' COMMENT '类型：text/image/emoji/audio/video/order/card/other（image/emoji/audio/video 为媒体消息，快照见 ext_json）',
  `content` TEXT COMMENT '消息内容',
  `task_id` BIGINT DEFAULT NULL COMMENT '关联回复任务 ID（仅 out 消息：创建任务即落库，按此列回填发送结果）',
  `receive_time` DATETIME NOT NULL COMMENT '闲鱼侧消息时间（OCR 估算）',
  `msg_status` VARCHAR(16) NOT NULL DEFAULT 'unread' COMMENT '状态：unread/read/replied（out 消息恒为 read，勿用 unread 以免污染左栏未读数）',
  `send_status` VARCHAR(16) DEFAULT NULL COMMENT '发送状态（仅 out）：sending/sent/failed/unknown，见 XianyuSendStatusEnum，NULL=存量已发出消息',
  `fail_reason` VARCHAR(500) DEFAULT '' COMMENT '发送失败/结果未知原因（仅 out，冗余自 reply_task.result_msg，避免聊天历史二次查任务表）',
  `ext_json` TEXT COMMENT '结构化快照（商品卡 ext：type/itemId/title/price/delivery/imageUrl/url；媒体消息：type=media + sourceMessageId?/items[{type,url,posterUrl,alt,duration,unavailableReason}]/dedupKey，前端渲染唯一数据源，13 号 §4.5）',
  `product_image_url` VARCHAR(512) DEFAULT NULL COMMENT '会话商品快照-主图 URL（取会话行右侧商品缩略图；左栏每行/会话头部展示，可空）',
  `product_price_text` VARCHAR(64) DEFAULT NULL COMMENT '会话商品快照-展示价文本（如 ¥1.00；取会话内最近一张商品卡价格，头部展示，可空）',
  `product_title` VARCHAR(255) DEFAULT NULL COMMENT '会话商品快照-商品标题（取会话行 sessionInfo.itemInfo.title；目录命中时优先目录标题，可空）',
  `creator` VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_conv_item` (`account_id`, `buyer_nick`, `item_id`, `receive_time`),
  KEY `idx_conv_chat` (`account_id`, `chat_id`, `receive_time`),
  KEY `idx_agent` (`agent_id`, `receive_time`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_dedup` (`agent_id`, `buyer_nick`, `receive_time`),
  KEY `idx_tenant_msg_status` (`tenant_id`, `msg_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闲鱼买家消息';

-- ④ 回复任务表（唯一外发通道）
DROP TABLE IF EXISTS `app_xianyu_reply_task`;
CREATE TABLE `app_xianyu_reply_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` BIGINT NOT NULL COMMENT '目标 Agent',
  `account_id` BIGINT NOT NULL COMMENT '目标闲鱼账号',
  `buyer_nick` VARCHAR(128) NOT NULL COMMENT '目标买家昵称',
  `message_id` BIGINT DEFAULT NULL COMMENT '回复哪条消息（可空 = 主动发起会话）',
  `item_id` BIGINT DEFAULT NULL COMMENT '会话绑定闲鱼商品 ID（回复定位弱校验入参，可空=未绑定商品会话不传；13 号 §4.3）',
  `chat_id` VARCHAR(64) DEFAULT NULL COMMENT '会话唯一标识（= 会话行 sessionInfo.sessionId；out 消息落库 convId 与 in 同源，防同买家多会话分裂）',
  `content` TEXT NOT NULL COMMENT '回复内容（媒体任务为占位文本）',
  `content_type` VARCHAR(16) NOT NULL DEFAULT 'text' COMMENT '内容类型：text/image/emoji',
  `ext_json` TEXT DEFAULT NULL COMMENT '媒体快照（type=media + items，与入站 ext_json 同契约）',
  `source` VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT '来源：manual（当前阶段）/ ai（预留）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '状态：pending/dispatched/success/failed/canceled/timeout',
  `result_msg` VARCHAR(500) DEFAULT '' COMMENT '执行结果说明（失败原因）',
  `verify_image_url` VARCHAR(512) DEFAULT '' COMMENT '发送后验证截图 URL（OSS）',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建人（管理端用户 ID）',
  `dispatched_at` DATETIME DEFAULT NULL COMMENT '下发时间（Agent 拉取）',
  `finished_at` DATETIME DEFAULT NULL COMMENT '完成时间',
  `creator` VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_status` (`status`),
  KEY `idx_agent_account` (`agent_id`, `account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闲鱼回复任务';

-- ⑤ Agent 运行事件表
DROP TABLE IF EXISTS `app_xianyu_agent_event`;
CREATE TABLE `app_xianyu_agent_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` BIGINT NOT NULL COMMENT 'Agent ID',
  `account_id` BIGINT DEFAULT NULL COMMENT '相关账号（可空）',
  `event_type` VARCHAR(32) NOT NULL COMMENT '类型：login_expired/screen_locked/ocr_failed/send_success/send_failed/send_unknown/client_error/agent_start/agent_stop',
  `level` VARCHAR(16) NOT NULL DEFAULT 'info' COMMENT '级别：info/warn/error',
  `content` VARCHAR(1000) DEFAULT '' COMMENT '事件描述',
  `raw_json` TEXT COMMENT '明细 JSON',
  `creator` VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（即事件时间）',
  `updater` VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_agent_time` (`agent_id`, `create_time`),
  KEY `idx_event_type` (`event_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闲鱼 Agent 运行事件（审计/告警）';

-- ============================================================================
-- 2. Agent 一次性注册码 — 原 app-xianyu-agent-enroll.sql
-- ============================================================================

DROP TABLE IF EXISTS `app_xianyu_agent_enroll`;
CREATE TABLE `app_xianyu_agent_enroll` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code_hash` VARCHAR(64) NOT NULL COMMENT '注册码 SHA-256（明文仅生成时下发一次，不入库不打日志）',
  `remark` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '备注（建议填目标机器名，如 xianguanjia-02）',
  `expire_time` DATETIME NOT NULL COMMENT '过期时间（超过即失效）',
  `used_time` DATETIME DEFAULT NULL COMMENT '被消费时间（NULL=未使用）',
  `agent_id` BIGINT DEFAULT NULL COMMENT '消费该码注册的 Agent ID（→ app_xianyu_agent.id，逻辑外键）',
  `creator` VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除（管理端撤销 = 逻辑删除，注册码立即失效）',
  `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '租户编号',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_expire_time` (`expire_time`),
  UNIQUE KEY `uk_code_hash` (`tenant_id`, `code_hash`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闲鱼 Agent 一次性注册码';

-- ============================================================================
-- 权限说明（无需新增菜单种子）
-- 注册码接口复用 Agent 管理页既有的权限串：
--   查询 app:xianyu-agent:query / 生成与撤销 app:xianyu-agent:update
-- 与 /app/xianyu/agent/page、/{id}/token/reset 同级，菜单 3037「Agent 管理」下。
-- ⚠️ 当前 dev/local 的 xianyuops.security.permission-check-enable=false，
--    所有 @ss.hasPermission 放行；恢复严格校验前须先建 type=3 按钮权限并给角色勾选。
-- ============================================================================

-- ============================================================================
-- 3. 菜单种子（3051 目录 + 3037/3038 页面）与角色绑定
--    目录 3051「客服消息」挂在闲鱼 parent_id=3003 下 sort 7，3037/3038 为其子页面
-- ============================================================================
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3051, '客服消息', '', 1, 7, 3003, 'service', 'ant-design:customer-service-outlined', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3037, 'Agent 管理', '', 2, 1, 3051, 'agent', 'ant-design:robot-outlined', 'app/xianyu/agent/index', 'AppXianyuAgent', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3038, '消息工作台', '', 2, 2, 3051, 'message', 'ant-design:message-outlined', 'app/xianyu/message/index', 'AppXianyuMessage', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');

-- 超级管理员角色绑定（其余角色按需在角色管理页面勾选）
INSERT INTO `system_role_menu` (`role_id`, `menu_id`, `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`) VALUES
(1, 3051, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3037, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3038, '1', NOW(), '1', NOW(), b'0', 1);

-- ============================================================================
-- 4. 定时任务注册（infra_job）
-- Agent 维护 Job：离线判定 + 回复任务超时兜底（每分钟）
-- id 41 为示例值；执行时按实际库中 infra_job 最大 id+1 调整
-- ============================================================================
INSERT INTO `infra_job` (`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`, `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (41, '闲鱼 Agent 维护 Job', 2, 'xianyuAgentMaintenanceJob', '', '0 * * * * ?', 0, 0, 0, '1', NOW(), '1', NOW(), b'0');

-- ⚠️ 执行后需到「基础设施 → 定时任务」界面手动开启该 Job（种子 status=2 为停用态），
--    否则离线判定与超时兜底不会运行。

-- ---------------------------------------------------------------------
-- 5. 定时任务注册说明
--    位置：管理后台「基础设施 → 定时任务 → 新增」
--    处理器名字：selfOrderMailRetryJob
--    CRON 表达式：0 * * * * ?   （每分钟）
--    参数：无
--    说明：付款钩子仅将邮件置为待发送，由本任务在付款后至少 30 秒延迟发送，
--          保证链接类订单的虚拟货源留档已建档、邮件内自助下单链接不丢失；
--          同时自动补发账号补配 notify_email 后的待发送单、重试发送失败单。
--          注册后先保持「停止」，后端发布上线后再开启。
-- ---------------------------------------------------------------------

-- ============================================================================
-- 6. 待执行：消息表冗余字段清理
-- ============================================================================

-- 消息表冗余字段清理（2026-09-24）：删除「只写不读」字段，避免单表过于复杂
-- 仅存量库需要执行（新库建库时已同步从上方 ③ 建表语句移除）；ALTER 不幂等，重复执行报 1091
-- 依据（全项目 grep 复核，两列均无任何读取/回放代码）：
--   · raw_json：仅 XianyuMessageServiceImpl.uploadOne 写 JsonUtils.toJsonString(item)，
--     TEXT 大字段、每条消息一份完整上报 JSON 副本，且各结构化字段已单独落库（ext_json 为前端渲染唯一真源）。
--   · client_msg_id：Agent 消息 hash / out 固定 task-{taskId}，无任何 SQL 查询；
--     排查用途已由 Agent 回执（UploadRespVO.clientMsgId）与日志 log.warn 覆盖。
-- 保留字段说明：buyer_id 虽当前零查询，但为昵称脱敏（x***0）塌缩场景的兜底识别维度，予以保留。
-- ⚠️ 执行顺序（重要）：必须先发布「已摘除 DO 字段 rawJson/clientMsgId 的后端」，再执行本 DDL。
--     两字段在插入时均恒为非空（MP 默认 NOT_NULL 策略会把非空字段拼进 INSERT），
--     旧后端在列被删后会报 1054 Unknown column，导致消息上报整体失败；
--     反之新后端连旧库也能跑（字段已不存在于 SQL），故发布在前、DDL 在后是唯一安全顺序。
-- ⚠️ 不可回滚：如需保留审计回放能力，请先另存（CREATE TABLE bak_xianyu_chat_message_raw
--     AS SELECT id, agent_id, buyer_nick, receive_time, raw_json, client_msg_id FROM app_xianyu_chat_message）。
-- ⏳ 尚未执行（待后端摘字段发布后执行）
-- ============================================================================
ALTER TABLE `app_xianyu_chat_message`
  DROP COLUMN `raw_json`,
  DROP COLUMN `client_msg_id`;

-- ============================================================================
-- 7. 待执行：外发图片/表情（2026-10-20，15 号文档）
-- ============================================================================
-- 新库已同步到上方 ①（app_xianyu_agent）与 ④（app_xianyu_reply_task）建表；
-- 存量库手动执行（ALTER 不幂等，重复执行报 1060）。
ALTER TABLE `app_xianyu_agent`
  ADD COLUMN `capabilities` VARCHAR(128) NOT NULL DEFAULT 'text'
    COMMENT '能力声明：text,image,emoji（旧 Agent 仅 text）' AFTER `agent_version`;

ALTER TABLE `app_xianyu_reply_task`
  ADD COLUMN `content_type` VARCHAR(16) NOT NULL DEFAULT 'text'
    COMMENT '内容类型：text/image/emoji' AFTER `content`,
  ADD COLUMN `ext_json` TEXT DEFAULT NULL
    COMMENT '媒体快照（type=media + items，与入站 ext_json 同契约）' AFTER `content_type`;

-- ----------------------------------------------------------------------------
-- 验证：SHOW TABLES LIKE 'app\_%'; 应包含本文件全部 6 张表
-- ----------------------------------------------------------------------------
