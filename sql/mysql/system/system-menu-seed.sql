-- =====================================================================
-- 管理后台菜单种子数据（system_menu）
-- =====================================================================
-- 用途：为「菜单随角色显隐」功能提供菜单数据基础。
--       前端路由为静态定义（web/src/router/routes/），本脚本将全部现有
--       页面录入 system_menu 表，path 与前端路由严格一致，
--       component_name 与路由 name 一致。登录后 /system/auth/get-permission-info
--       返回的菜单树将用于前端侧边栏与路由访问过滤。
--
-- 执行说明：
--   1. 本脚本幂等，可重复执行（先清理 3000~3036、3052 区间数据再插入）。
--   2. ID 区间 3002/3003/3009、3021~3033，另含 3052（服务配置）；避开 base.sql 中芋道自带菜单 ID；
--      3037~3051 由其它 seed（app/app-xianyu.sql §6 / system-tenant-menu-seed.sql）占用，本脚本不得清理。
--   3. 仅录入目录（type=1）与页面（type=2）两级，不含按钮级权限（type=3）。
--   4. 末尾将全部菜单绑定给超级管理员角色（role_id=1）。若超管角色 ID
--      不是 1，请先在 system_role 表中确认后再执行绑定部分。
--   5. 执行顺序：整个脚本从上到下顺序执行即可。
--   6. 执行完成后，给普通角色在「角色管理 → 分配权限」中勾选菜单，
--      对应用户重新登录后侧边栏即按角色过滤。
-- =====================================================================

-- 1. 清理旧数据（菜单 + 角色菜单绑定），保证幂等
--    ⚠️ 仅清理本脚本自身的 3000~3036 与 3052；不要扩大到 3099，否则会连带删除
--       app/app-xianyu.sql §6（3037/3038/3051）与 system-tenant-menu-seed.sql（3039~3050）的菜单。
DELETE FROM `system_role_menu` WHERE `menu_id` BETWEEN 3000 AND 3036 OR `menu_id` = 3052;
DELETE FROM `system_menu` WHERE `id` BETWEEN 3000 AND 3036 OR `id` = 3052;

-- 2. 目录 + 页面菜单数据
-- 仪表盘

-- 应用管理
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3002, '应用管理', '', 1, 5, 0, '/app', 'ant-design:appstore-outlined', NULL, NULL, 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3003, '闲鱼', '', 1, 1, 3002, 'xianyu', 'ant-design:shop-outlined', NULL, NULL, 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3009, '货源管理', '', 2, 6, 3003, 'source', 'ant-design:search-outlined', 'app/xianyu/source/index', 'AppXianyuSource', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');


-- 系统管理
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3021, '系统管理', '', 1, 10, 0, '/system', 'ant-design:setting-outlined', NULL, NULL, 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3022, '用户管理', '', 2, 1, 3021, 'user', 'ant-design:user-outlined', 'system/user/index', 'SystemUser', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3023, '角色管理', '', 2, 2, 3021, 'role', 'ant-design:team-outlined', 'system/role/index', 'SystemRole', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3024, '菜单管理', '', 2, 3, 3021, 'menu', 'ant-design:menu-outlined', 'system/menu/index', 'SystemMenu', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3025, '部门管理', '', 2, 4, 3021, 'dept', 'ant-design:apartment-outlined', 'system/dept/index', 'SystemDept', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3026, '岗位管理', '', 2, 5, 3021, 'post', 'ant-design:idcard-outlined', 'system/post/index', 'SystemPost', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3027, '字典管理', '', 2, 6, 3021, 'dict', 'ant-design:book-outlined', 'system/dict/index', 'SystemDict', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3028, '通知公告', '', 2, 7, 3021, 'notice', 'ant-design:notification-outlined', 'system/notice/index', 'SystemNotice', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3029, '操作日志', '', 2, 8, 3021, 'operate-log', 'ant-design:file-text-outlined', 'system/operatelog/index', 'SystemOperateLog', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3030, '登录日志', '', 2, 9, 3021, 'login-log', 'ant-design:login-outlined', 'system/loginlog/index', 'SystemLoginLog', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');

-- 基础设施
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3031, '基础设施', '', 1, 20, 0, '/infra', 'ant-design:tool-outlined', NULL, NULL, 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3032, '文件管理', '', 2, 1, 3031, 'file', 'ant-design:folder-outlined', 'infra/file/index', 'InfraFile', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3033, '定时任务', '', 2, 2, 3031, 'job', 'ant-design:clock-circle-outlined', 'infra/job/index', 'InfraJob', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');

INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES (3052, '服务配置', '', 2, 8, 3003, 'config', 'ant-design:setting-outlined', 'app/config/index', 'AppConfig', 0, b'1', b'1', b'1', '1', '2026-08-14 00:00:00', '1', '2026-08-14 00:00:00', b'0');

-- 3. 超级管理员角色（role_id=1）绑定全部菜单
INSERT INTO `system_role_menu` (`role_id`, `menu_id`, `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`) VALUES
(1, 3002, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3003, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3009, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3021, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3022, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3023, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3024, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3025, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3026, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3027, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3028, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3029, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3030, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3031, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3032, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3033, '1', NOW(), '1', NOW(), b'0', 1),
(1, 3052, '1', NOW(), '1', NOW(), b'0', 1);
