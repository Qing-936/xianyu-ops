import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemUserApi {
  export interface User {
    id?: number;
    username: string;
    nickname: string;
    deptId: number;
    deptName?: string;
    postIds: number[];
    email: string;
    mobile: string;
    sex: number;
    avatar: string;
    loginIp: string;
    status: number;
    remark: string;
    createTime?: string;
  }
}

/** 查询用户管理列表 */
export function getUserPage(params: PageParam) {
  return requestClient.get<PageResult<SystemUserApi.User>>('/system/user/page', { params });
}

/** 查询用户详情 */
export function getUser(id: number) {
  return requestClient.get<SystemUserApi.User>(`/system/user/get?id=${id}`);
}

/** 新增用户 */
export function createUser(data: SystemUserApi.User) {
  return requestClient.post('/system/user/create', data);
}

/** 修改用户 */
export function updateUser(data: SystemUserApi.User) {
  return requestClient.put('/system/user/update', data);
}

/** 删除用户 */
export function deleteUser(id: number) {
  return requestClient.delete(`/system/user/delete?id=${id}`);
}

/** 用户密码重置 */
export function resetUserPassword(id: number, password: string) {
  return requestClient.put('/system/user/update-password', { id, password });
}

/** 用户状态修改 */
export function updateUserStatus(id: number, status: number) {
  return requestClient.put('/system/user/update-status', { id, status });
}

/** 获取用户精简信息列表 */
export function getSimpleUserList() {
  return requestClient.get<SystemUserApi.User[]>('/system/user/simple-list');
}
