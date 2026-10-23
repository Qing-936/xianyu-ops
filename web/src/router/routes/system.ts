import type { RouteRecordRaw } from 'vue-router';

/**
 * 系统管理路由 — 静态菜单定义
 * 核心变更：菜单结构由前端静态定义，不再从后端动态获取
 * 新增功能时只需在此文件中添加路由记录，无需在数据库菜单表中配置
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/system',
    name: 'System',
    meta: {
      icon: 'ant-design:setting-outlined',
      order: 10,
      title: '系统管理',
    },
    children: [
      {
        name: 'SystemUser',
        path: 'user',
        component: () => import('#/views/system/user/index.vue'),
        meta: {
          icon: 'ant-design:user-outlined',
          title: '用户管理',
        },
      },
      {
        name: 'SystemRole',
        path: 'role',
        component: () => import('#/views/system/role/index.vue'),
        meta: {
          icon: 'ant-design:team-outlined',
          title: '角色管理',
        },
      },
      {
        name: 'SystemMenu',
        path: 'menu',
        component: () => import('#/views/system/menu/index.vue'),
        meta: {
          icon: 'ant-design:menu-outlined',
          title: '菜单管理',
        },
      },
      {
        name: 'SystemDept',
        path: 'dept',
        component: () => import('#/views/system/dept/index.vue'),
        meta: {
          icon: 'ant-design:apartment-outlined',
          title: '部门管理',
        },
      },
      {
        name: 'SystemPost',
        path: 'post',
        component: () => import('#/views/system/post/index.vue'),
        meta: {
          icon: 'ant-design:idcard-outlined',
          title: '岗位管理',
        },
      },
      {
        name: 'SystemDict',
        path: 'dict',
        component: () => import('#/views/system/dict/index.vue'),
        meta: {
          icon: 'ant-design:book-outlined',
          title: '字典管理',
        },
      },
      {
        name: 'SystemNotice',
        path: 'notice',
        component: () => import('#/views/system/notice/index.vue'),
        meta: {
          icon: 'ant-design:notification-outlined',
          title: '通知公告',
        },
      },
      {
        name: 'SystemOperateLog',
        path: 'operate-log',
        component: () => import('#/views/system/operatelog/index.vue'),
        meta: {
          icon: 'ant-design:file-text-outlined',
          title: '操作日志',
        },
      },
      {
        name: 'SystemLoginLog',
        path: 'login-log',
        component: () => import('#/views/system/loginlog/index.vue'),
        meta: {
          icon: 'ant-design:login-outlined',
          title: '登录日志',
        },
      },
      {
        name: 'SystemTenant',
        path: 'tenant',
        component: () => import('#/views/system/tenant/index.vue'),
        meta: {
          icon: 'ant-design:home-outlined',
          title: '租户管理',
        },
      },
      {
        name: 'SystemTenantPackage',
        path: 'tenant-package',
        component: () => import('#/views/system/tenantPackage/index.vue'),
        meta: {
          icon: 'ant-design:appstore-add-outlined',
          title: '租户套餐',
        },
      },
    ],
  },
];

export default routes;
