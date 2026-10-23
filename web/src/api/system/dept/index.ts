import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemDeptApi {
  export interface Dept {
    id?: number;
    name: string;
    parentId: number;
    sort: number;
    leaderUserId: number;
    phone: string;
    email: string;
    status: number;
    createTime?: string;
  }
}

/** 查询部门列表 */
export function getDeptList(params?: any) {
  return requestClient.get<SystemDeptApi.Dept[]>('/system/dept/list', { params });
}

/** 查询部门（精简)列表 */
export function getSimpleDeptList() {
  return requestClient.get<SystemDeptApi.Dept[]>('/system/dept/simple-list');
}

/** 查询部门详情 */
export function getDept(id: number) {
  return requestClient.get<SystemDeptApi.Dept>(`/system/dept/get?id=${id}`);
}

/** 新增部门 */
export function createDept(data: SystemDeptApi.Dept) {
  return requestClient.post('/system/dept/create', data);
}

/** 修改部门 */
export function updateDept(data: SystemDeptApi.Dept) {
  return requestClient.put('/system/dept/update', data);
}

/** 删除部门 */
export function deleteDept(id: number) {
  return requestClient.delete(`/system/dept/delete?id=${id}`);
}
