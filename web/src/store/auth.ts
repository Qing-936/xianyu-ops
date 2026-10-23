import { defineStore } from 'pinia';
import { loginApi, logoutApi, getAuthPermissionInfoApi } from '#/api/core/auth';
import type { AuthApi } from '#/api/core/auth';
import { useUserStore } from './user';
import { usePermissionStore } from './permission';
import { useTenantStore } from './tenant';

const LOGIN_PATH = '/auth/login';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null as null | string,
    refreshToken: null as null | string,
    loginLoading: false,
  }),

  getters: {
    isAuthenticated: (state) => !!state.accessToken,
  },

  actions: {
    /** 登录 */
    async login(params: AuthApi.LoginParams) {
      this.loginLoading = true;
      try {
        const result = await loginApi(params);
        this.accessToken = result.accessToken;
        this.refreshToken = result.refreshToken;

        // 登录后立即获取用户信息和权限
        await this.fetchUserInfoAndPermissions();

        return result;
      } finally {
        this.loginLoading = false;
      }
    },

    /** 获取用户信息和权限 */
    async fetchUserInfoAndPermissions() {
      const permissionInfo = await getAuthPermissionInfoApi();

      // 存储用户信息
      const userStore = useUserStore();
      userStore.setUserInfo(permissionInfo.user);
      userStore.setRoles(permissionInfo.roles);

      // 存储权限码（用于按钮级权限控制）
      const permissionStore = usePermissionStore();
      permissionStore.setPermissions(permissionInfo.permissions);

      // 存储用户菜单（用于菜单随角色显隐）；
      // 超管角色后端返回全表菜单（含无效记录），直接跳过过滤以免误隐藏页面
      const isSuperAdmin = (permissionInfo.roles || []).includes('super_admin');
      permissionStore.setUserMenus(isSuperAdmin ? [] : permissionInfo.menus || [], !isSuperAdmin);
    },

    /** 登出 */
    async logout(redirect: boolean = true) {
      try {
        if (this.accessToken) {
          await logoutApi(this.accessToken);
        }
      } catch {
        // 忽略登出接口错误
      }

      this.accessToken = null;
      this.refreshToken = null;

      // 清除其他 store
      const userStore = useUserStore();
      userStore.$reset();
      const permissionStore = usePermissionStore();
      permissionStore.$reset();
      // 租户：仅清访问租户，保留 tenantId 供登录页记住上次租户
      const tenantStore = useTenantStore();
      tenantStore.clearVisitTenant();

      // 跳转登录页（使用 window.location.replace，最可靠的全量刷新方式）
      if (redirect) {
        // hash 路由下 pathname 不含路由部分，须从 hash 取当前页面路径，跳转目标也必须带 # 前缀
        const currentPath = window.location.hash.replace(/^#/, '') || '/';
        const query = currentPath === '/' ? '' : `?redirect=${encodeURIComponent(currentPath)}`;
        window.location.replace(`#${LOGIN_PATH}${query}`);
      }
    },
  },

  persist: {
    key: 'xianyuops-auth',
    pick: ['accessToken', 'refreshToken'],
  },
});
