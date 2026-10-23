import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router';
import { coreRoutes, fallbackNotFoundRoute } from './routes';
import systemRoutes from './routes/system';
import infraRoutes from './routes/infra';
import appRoutes from './routes/app';
import { createRouterGuard } from './guard';

/** 业务路由（静态定义，从路由文件中获取菜单结构） */
const businessRoutes = [
  ...systemRoutes,
  ...infraRoutes,
  ...appRoutes,
];

/** 全部路由 */
const routes = [
  ...coreRoutes,
  fallbackNotFoundRoute,
];

const router = createRouter({
  history:
    import.meta.env.VITE_ROUTER_HISTORY === 'hash'
      ? createWebHashHistory(import.meta.env.VITE_BASE)
      : createWebHistory(import.meta.env.VITE_BASE),
  routes,
  scrollBehavior: (to, _from, savedPosition) => {
    if (savedPosition) return savedPosition;
    return to.hash ? { behavior: 'smooth', el: to.hash } : { left: 0, top: 0 };
  },
});

// 将业务路由动态添加到 Root 路由下
const rootRoute = router.getRoutes().find((r) => r.name === 'Root');
if (rootRoute) {
  for (const route of businessRoutes) {
    router.addRoute('Root', route);
  }
}

// 创建路由守卫
createRouterGuard(router);

export { router, businessRoutes };
