import type { RouteRecordRaw } from 'vue-router';
import { DEFAULT_HOME_PATH } from './core';

const BasicLayout = () => import('#/layouts/BasicLayout.vue');
const AuthLayout = () => import('#/layouts/AuthLayout.vue');

/** 404 兜底路由 */
export const fallbackNotFoundRoute: RouteRecordRaw = {
  component: () => import('#/views/_core/fallback/not-found.vue'),
  meta: { hideInBreadcrumb: true, hideInMenu: true, hideInTab: true, title: '404' },
  name: 'FallbackNotFound',
  path: '/:path(.*)*',
};

/** 基础路由 — 不需要权限拦截 */
export const coreRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    component: BasicLayout,
    meta: { hideInBreadcrumb: true, title: 'Root' },
    name: 'Root',
    redirect: DEFAULT_HOME_PATH,
    children: [],
  },
  {
    path: '/auth',
    component: AuthLayout,
    meta: { hideInTab: true, title: '认证' },
    name: 'Authentication',
    redirect: '/auth/login',
    children: [
      {
        name: 'Login',
        path: 'login',
        component: () => import('#/views/_core/authentication/login.vue'),
        meta: { title: '登录' },
      },
    ],
  },
];

/** 基础路由名称列表（不需要权限拦截） */
export const coreRouteNames = coreRoutes
  .map((route) => route.name)
  .filter(Boolean) as string[];
