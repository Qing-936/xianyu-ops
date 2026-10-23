import { requestClient } from '#/utils/request';

export namespace SystemPermissionApi {
  /** 分配用户角色请求 */
  export interface AssignUserRoleReqVO {
    userId: number;
    roleIds: number[];
  }

  /** 分配角色菜单请求 */
  export interface AssignRoleMenuReqVO {
    roleId: number;
    menuIds: number[];
  }

  /** 分配角色菜单响应：removedMenuIds 为被当前租户套餐剔除、实际未生效的菜单 */
  export interface AssignRoleMenuRespVO {
    removedMenuIds: number[];
  }

  /** 分配角色数据权限请求 */
  export interface AssignRoleDataScopeReqVO {
    roleId: number;
    dataScope: number;
    dataScopeDeptIds: number[];
  }
}

/** 查询角色拥有的菜单权限 */
export function getRoleMenuList(roleId: number) {
  return requestClient.get<number[]>(`/system/permission/list-role-menus?roleId=${roleId}`);
}

/** 赋予角色菜单权限 */
export function assignRoleMenu(data: SystemPermissionApi.AssignRoleMenuReqVO) {
  return requestClient.post<SystemPermissionApi.AssignRoleMenuRespVO>('/system/permission/assign-role-menu', data);
}

/** 赋予角色数据权限 */
export function assignRoleDataScope(data: SystemPermissionApi.AssignRoleDataScopeReqVO) {
  return requestClient.post('/system/permission/assign-role-data-scope', data);
}

/** 查询用户拥有的角色数组 */
export function getUserRoleList(userId: number) {
  return requestClient.get<number[]>(`/system/permission/list-user-roles?userId=${userId}`);
}

/** 赋予用户角色 */
export function assignUserRole(data: SystemPermissionApi.AssignUserRoleReqVO) {
  return requestClient.post('/system/permission/assign-user-role', data);
}
