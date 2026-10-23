-- =====================================================================
-- 租户管理菜单种子数据（system_menu）
-- =====================================================================
-- 用途：为管理后台「租户管理 / 租户套餐」页面提供菜单与按钮权限数据。
--       前端路由为静态定义（web/src/router/routes/system.ts），本脚本录入的
--       path 与前端路由严格一致（/system/tenant、/system/tenant-package），
--       component_name 与路由 name 一致（SystemTenant、SystemTenantPackage）。
--
-- 执行说明：
--   1. 本脚本幂等，可重复执行（先清理 3039~3050 区间数据再插入）。
--   2. ID 区间 3039~3050：已跨全部 sql/**/*.sql 的 system_menu INSERT 核验，
--      3037/3038/3051 已被 app/app-xianyu.sql §6 占用，3039 起空闲。
--   3. ⚠️ 与 system-menu-seed.sql 的差异：本脚本包含按钮级权限（type=3），
--      因为租户功能的按钮显隐（前端 hasPermission）与后端 @PreAuthorize
--      均依赖权限码（system:tenant:* / system:tenant-package:* / system:tenant:visit）。
--   4. ⚠️ system-menu-seed.sql 的清理语句已收敛为只删 3000~3036（其自身区间），
--      不再波及本脚本的 3039~3050；两者可独立重复执行，互不覆盖。
--   5. 末尾将全部菜单绑定给超级管理员角色（role_id=1）。普通角色需在
--      「角色管理 → 分配权限」中勾选后，对应用户重新登录生效。
--   6. system:tenant:visit（3045 租户切换）决定顶栏"访问租户"切换下拉是否可见。
-- =====================================================================

-- 1. 清理旧数据（菜单 + 角色菜单绑定），保证幂等
DELETE FROM `system_role_menu` WHERE `menu_id` BETWEEN 3039 AND 3050;
DELETE FROM `system_menu` WHERE `id` BETWEEN 3039 AND 3050;

-- 2. 租户管理（parent 3021 = 系统管理目录，现有子菜单 sort 1-9，新菜单排 10/11）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3039, '租户管理', '', 2, 10, 3021, 'tenant', 'ant-design:home-outlined', 'system/tenant/index', 'SystemTenant', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3040, '租户查询', 'system:tenant:query', 3, 1, 3039, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3041, '租户创建', 'system:tenant:create', 3, 2, 3039, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3042, '租户更新', 'system:tenant:update', 3, 3, 3039, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3043, '租户删除', 'system:tenant:delete', 3, 4, 3039, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3044, '租户导出', 'system:tenant:export', 3, 5, 3039, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3045, '租户切换', 'system:tenant:visit', 3, 99, 3039, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');

-- 3. 租户套餐
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3046, '租户套餐', '', 2, 11, 3021, 'tenant-package', 'ant-design:appstore-add-outlined', 'system/tenantPackage/index', 'SystemTenantPackage', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3047, '套餐查询', 'system:tenant-package:query', 3, 1, 3046, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3048, '套餐创建', 'system:tenant-package:create', 3, 2, 3046, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3049, '套餐更新', 'system:tenant-package:update', 3, 3, 3046, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3050, '套餐删除', 'system:tenant-package:delete', 3, 4, 3046, '', '', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');

-- 4. 超级管理员角色（role_id=1）绑定全部菜单
INSERT INTO `system_role_menu` (`role_id`, `menu_id`, `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`) VALUES
(1, 3039, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3040, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3041, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3042, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3043, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3044, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3045, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3046, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3047, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3048, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3049, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3050, '1', NOW(), '1', NOW(), b'0', 1);
