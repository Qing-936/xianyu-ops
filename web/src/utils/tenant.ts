import { useTenantStore } from '#/store/tenant';

/** 租户功能开关（VITE_APP_TENANT_ENABLE === 'true'） */
export function isTenantEnable(): boolean {
  return import.meta.env.VITE_APP_TENANT_ENABLE === 'true';
}

/** 兜底租户 ID（VITE_APP_TENANT_ID，默认 1；开关关闭或 store 未初始化时使用） */
export function getFallbackTenantId(): number {
  const id = Number(import.meta.env.VITE_APP_TENANT_ID);
  return Number.isFinite(id) && id > 0 ? id : 1;
}

/**
 * 注入租户请求头（axios 拦截器与 agent-chat fetch 链路共用，保证行为一致）：
 * - tenant-id：登录租户（store.tenantId，未设置时回退兜底租户）
 * - visit-tenant-id：访问租户（顶栏切换后携带，平台租户视角不携带）
 *
 * 开关关闭时退回单租户行为：仅携带兜底 tenant-id。
 */
export function applyTenantHeaders(headers: Record<string, unknown>): void {
  if (!isTenantEnable()) {
    headers['tenant-id'] = getFallbackTenantId();
    return;
  }
  const tenantStore = useTenantStore();
  headers['tenant-id'] = tenantStore.tenantId ?? getFallbackTenantId();
  if (tenantStore.visitTenantId !== null) {
    headers['visit-tenant-id'] = tenantStore.visitTenantId;
  }
}
