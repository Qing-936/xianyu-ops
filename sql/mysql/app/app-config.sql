-- ============================================================================
-- 服务配置（Service Config）
-- 说明：以「服务 → 配置文件」两级组织，运营在线编辑文件内容，保存即生效。
-- 本期仅启用 source_clean（货源 AI 清洗），运营助手等后续服务按同一结构扩展。
-- 表结构变更请同步回写本文件，不再新增一次性增量脚本。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `app_config_file` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `service_code` VARCHAR(64)  COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务编码，如 source_clean',
  `file_name`    VARCHAR(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件名，如 skills.md',
  `tag`          VARCHAR(16)  COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '标签：prompt/config/template/data',
  `description`  VARCHAR(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '文件用途说明',
  `content`      LONGTEXT     NOT NULL COMMENT '当前内容',
  `modified`     BIT(1)       NOT NULL DEFAULT b'0' COMMENT '是否被人工修改（0=跟随资源默认，1=已修改不再被覆盖）',
  `sort_order`   INT          NOT NULL DEFAULT 0 COMMENT '排序',
  `creator`      VARCHAR(64)  COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`      VARCHAR(64)  COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      BIT(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`    BIGINT       NOT NULL DEFAULT '0' COMMENT '租户编号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_service_file` (`tenant_id`, `service_code`, `file_name`, `deleted`),
  KEY `idx_tenant_service` (`tenant_id`, `service_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务配置 - 配置文件';
