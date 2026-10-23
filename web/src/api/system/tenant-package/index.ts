import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemTenantPackageApi {
  /** 租户套餐信息 */
  export interface TenantPackage {
    id?: number;
    /** 套餐名称 */
    name: string;
    /** 套餐状态（common_status 字典：0 正常 / 1 停用） */
    status: number;
    /** 备注 */
    remark?: string;
    /** 关联菜单编号集合 */
    menuIds: number[];
    createTime?: string;
  }

  /** 租户套餐分页查询参数 */
  export interface TenantPackagePageParams extends PageParam {
    name?: string;
    status?: number;
    createTime?: [string, string];
  }

  /** 租户套餐精简信息 */
  export interface TenantPackageSimple {
    id: number;
    name: string;
  }
}

/** 查询租户套餐分页 */
export function getTenantPackagePage(params: SystemTenantPackageApi.TenantPackagePageParams) {
  return requestClient.get<PageResult<SystemTenantPackageApi.TenantPackage>>('/system/tenant-package/page', { params });
}

/** 查询租户套餐详情 */
export function getTenantPackage(id: number) {
  return requestClient.get<SystemTenantPackageApi.TenantPackage>(`/system/tenant-package/get?id=${id}`);
}

/** 新增租户套餐 */
export function createTenantPackage(data: SystemTenantPackageApi.TenantPackage) {
  return requestClient.post<number>('/system/tenant-package/create', data);
}

/** 修改租户套餐 */
export function updateTenantPackage(data: SystemTenantPackageApi.TenantPackage) {
  return requestClient.put<boolean>('/system/tenant-package/update', data);
}

/** 删除租户套餐 */
export function deleteTenantPackage(id: number) {
  return requestClient.delete<boolean>(`/system/tenant-package/delete?id=${id}`);
}

/** 批量删除租户套餐 */
export function deleteTenantPackageList(ids: number[]) {
  return requestClient.delete<boolean>(`/system/tenant-package/delete-list?ids=${ids.join(',')}`);
}

/** 查询租户套餐精简列表（用于租户表单套餐下拉） */
export function getSimpleTenantPackageList() {
  return requestClient.get<SystemTenantPackageApi.TenantPackageSimple[]>('/system/tenant-package/get-simple-list');
}
