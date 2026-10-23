import { requestClient } from '#/utils/request';

export namespace SystemAreaApi {
  export interface AreaNode {
    id: number;
    name: string;
    children?: AreaNode[];
  }
}

/** 获得地区树 */
export function getAreaTree() {
  return requestClient.get<SystemAreaApi.AreaNode[]>('/system/area/tree');
}
