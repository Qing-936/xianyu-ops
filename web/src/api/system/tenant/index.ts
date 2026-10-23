import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';
import type { AxiosResponse } from 'axios';

export namespace SystemTenantApi {
  /** 租户信息 */
  export interface Tenant {
    id?: number;
    /** 租户名称 */
    name: string;
    /** 租户套餐编号（0 = 系统租户） */
    packageId?: number;
    /** 联系人 */
    contactName: string;
    /** 联系手机 */
    contactMobile?: string;
    /** 租户状态（common_status 字典：0 正常 / 1 停用） */
    status: number;
    /** 绑定域名（多域名数组，勿带协议/路径） */
    websites?: string[];
    /** 过期时间（毫秒时间戳字符串；DatePicker value-format="x"，后端返回 number 时前端统一转 string） */
    expireTime?: string;
    /** 账号额度 */
    accountCount?: number;
    /** 用户名称（仅创建租户时必填） */
    username?: string;
    /** 用户密码（仅创建租户时必填） */
    password?: string;
    createTime?: string;
  }

  /** 租户分页查询参数 */
  export interface TenantPageParams extends PageParam {
    name?: string;
    contactName?: string;
    contactMobile?: string;
    status?: number;
    createTime?: [string, string];
  }
}

/** 查询租户分页 */
export function getTenantPage(params: SystemTenantApi.TenantPageParams) {
  return requestClient.get<PageResult<SystemTenantApi.Tenant>>('/system/tenant/page', { params });
}

/** 查询租户详情 */
export function getTenant(id: number) {
  return requestClient.get<SystemTenantApi.Tenant>(`/system/tenant/get?id=${id}`);
}

/** 新增租户 */
export function createTenant(data: SystemTenantApi.Tenant) {
  return requestClient.post<number>('/system/tenant/create', data);
}

/** 修改租户 */
export function updateTenant(data: SystemTenantApi.Tenant) {
  return requestClient.put<boolean>('/system/tenant/update', data);
}

/** 删除租户 */
export function deleteTenant(id: number) {
  return requestClient.delete<boolean>(`/system/tenant/delete?id=${id}`);
}

/** 批量删除租户 */
export function deleteTenantList(ids: number[]) {
  return requestClient.delete<boolean>(`/system/tenant/delete-list?ids=${ids.join(',')}`);
}

/** 查询租户精简列表（免登录，用于登录页租户下拉） */
export function getSimpleTenantList() {
  return requestClient.get<SystemTenantApi.Tenant[]>('/system/tenant/simple-list', { __silent: true });
}

/** 根据租户名查询租户编号 */
export function getTenantIdByName(name: string) {
  return requestClient.get<number>(`/system/tenant/get-id-by-name?name=${encodeURIComponent(name)}`);
}

/** 根据域名查询租户（免登录，用于登录页域名匹配） */
export function getTenantByWebsite(website: string) {
  return requestClient.get<SystemTenantApi.Tenant | null>(
    `/system/tenant/get-by-website?website=${encodeURIComponent(website)}`,
    { __silent: true },
  );
}

/** 导出租户 Excel（blob 响应返回完整 AxiosResponse） */
export function exportTenantExcel(params: Omit<SystemTenantApi.TenantPageParams, 'pageNo' | 'pageSize'>) {
  return requestClient.get<AxiosResponse<Blob>>('/system/tenant/export-excel', {
    params,
    responseType: 'blob',
  });
}
