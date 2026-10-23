import { requestClient } from '#/utils/request';

export namespace SystemMenuApi {
  /** 菜单信息 */
  export interface Menu {
    id: number;
    name: string;
    permission: string;
    type: number;
    sort: number;
    parentId: number;
    path: string;
    icon: string;
    component: string;
    componentName?: string;
    status: number;
    visible: boolean;
    keepAlive: boolean;
    alwaysShow?: boolean;
    createTime: string;
    children?: Menu[];
  }
}

/** 查询菜单（精简）列表 */
export function getSimpleMenusList() {
  return requestClient.get<SystemMenuApi.Menu[]>('/system/menu/simple-list');
}

/** 查询菜单列表 */
export function getMenuList(params?: Record<string, any>) {
  return requestClient.get<SystemMenuApi.Menu[]>('/system/menu/list', { params });
}

/** 新增菜单 */
export function createMenu(data: Partial<SystemMenuApi.Menu>) {
  return requestClient.post('/system/menu/create', data);
}

/** 修改菜单 */
export function updateMenu(data: Partial<SystemMenuApi.Menu>) {
  return requestClient.put('/system/menu/update', data);
}

/** 删除菜单 */
export function deleteMenu(id: number) {
  return requestClient.delete(`/system/menu/delete?id=${id}`);
}
