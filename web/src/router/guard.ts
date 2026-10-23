import type { Router } from 'vue-router';
import NProgress from 'nprogress';
import 'nprogress/nprogress.css';
import { LOGIN_PATH } from './routes/core';
import { DEFAULT_HOME_PATH } from './routes/core';
import { WELCOME_PATH } from './routes/core';
import { useAuthStore } from '#/store/auth';
import { useDictStore } from '#/store/dict';
import { usePermissionStore } from '#/store/permission';

NProgress.configure({ showSpinner: false });

/** 不受菜单权限过滤的路径（任意登录用户可访问） */
const ALWAYS_ALLOWED_PATHS = ['/profile', WELCOME_PATH];

/** 通用守卫 */
function setupCommonGuard(router: Router) {
  const loadedPaths = new Set<string>();

  router.beforeEach((to) => {
    to.meta.loaded = loadedPaths.has(to.path);
    if (!to.meta.loaded) {
      NProgress.start();
    }
    return true;
  });

  router.afterEach((to) => {
    loadedPaths.add(to.path);
    NProgress.done();
  });
}

/** 权限守卫 */
function setupAccessGuard(router: Router) {
  router.beforeEach(async (to, from) => {
    const authStore = useAuthStore();

    // 基础路由不需要拦截
    if (to.path.startsWith('/auth') || to.path === '/:path(.*)*') {
      // 已登录访问登录页，跳转首页
      if (to.path === LOGIN_PATH && authStore.isAuthenticated) {
        return DEFAULT_HOME_PATH;
      }
      return true;
    }

    // 未登录，跳转登录页
    if (!authStore.isAuthenticated) {
      return {
        path: LOGIN_PATH,
        query: to.fullPath === DEFAULT_HOME_PATH ? {} : { redirect: encodeURIComponent(to.fullPath) },
        replace: true,
      };
    }

    // 已登录，加载菜单权限（页面刷新后持久化数据已有则跳过）
    const permissionStore = usePermissionStore();
    if (!permissionStore.menuLoaded && !permissionStore.menuLoadAttempted) {
      permissionStore.markMenuLoadAttempted();
      try {
        await authStore.fetchUserInfoAndPermissions();
      } catch {
        // 权限信息加载失败不阻塞页面，菜单过滤保持关闭
      }
    }

    // 菜单随角色显隐：目标路径不在被分配菜单内时，优先跳到用户菜单中第一个可访问页面（登录后/带 redirect 均兜底，避免 404）；
    // 用户没有任何可访问页面时，落到欢迎页（白名单路径）；真正未知的路径仍由 FallbackNotFound 处理
    if (permissionStore.menuFilterEnabled && to.name !== 'FallbackNotFound') {
      const activePath = to.meta.activePath as string | undefined;
      const checkPath = activePath || to.path;
      if (!ALWAYS_ALLOWED_PATHS.includes(checkPath) && !permissionStore.userMenuPaths.includes(checkPath)) {
        const firstPath = permissionStore.firstLeafMenuPath;
        if (firstPath) return firstPath;
        return WELCOME_PATH;
      }
    }

    // 已登录，加载字典数据
    const dictStore = useDictStore();
    if (!dictStore.loaded) {
      try {
        await dictStore.loadDictData();
      } catch {
        // 字典加载失败不阻塞页面
      }
    }

    return true;
  });
}

/** 创建路由守卫 */
export function createRouterGuard(router: Router) {
  setupCommonGuard(router);
  setupAccessGuard(router);
}
