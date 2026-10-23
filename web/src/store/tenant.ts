import { defineStore } from 'pinia';

/**
 * 租户状态 store。
 *
 * - tenantId：登录租户（登录页选择/域名匹配后写入，持久化，登出保留以"记住上次租户"）
 * - visitTenantId：访问租户（顶栏切换，请求头 visit-tenant-id，登出清除）
 *
 * 注意：本 store 不允许 import 任何 api 模块，避免 request.ts -> store -> api -> request.ts 循环依赖。
 */
export const useTenantStore = defineStore('tenant', {
  state: () => ({
    tenantId: null as null | number,
    visitTenantId: null as null | number,
  }),

  actions: {
    /** 设置登录租户 */
    setTenantId(id: null | number) {
      this.tenantId = id;
    },

    /** 设置访问租户（null = 回到平台租户视角） */
    setVisitTenantId(id: null | number) {
      this.visitTenantId = id;
    },

    /** 登出清理：仅清访问租户，保留 tenantId 供登录页记住上次租户 */
    clearVisitTenant() {
      this.visitTenantId = null;
    },
  },

  persist: {
    key: 'xianyuops-tenant',
    pick: ['tenantId', 'visitTenantId'],
  },
});
