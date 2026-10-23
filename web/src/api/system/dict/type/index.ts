import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemDictTypeApi {
  export type DictType = {
    createTime: string;
    id?: number;
    name: string;
    remark: string;
    status: number;
    type: string;
  };
}

/** 查询字典（精简)列表 */
export function getSimpleDictTypeList() {
  return requestClient.get<SystemDictTypeApi.DictType[]>('/system/dict-type/list-all-simple');
}

/** 查询字典列表 */
export function getDictTypePage(params: PageParam) {
  return requestClient.get<PageResult<SystemDictTypeApi.DictType>>('/system/dict-type/page', { params });
}

/** 查询字典详情 */
export function getDictType(id: number) {
  return requestClient.get<SystemDictTypeApi.DictType>(`/system/dict-type/get?id=${id}`);
}

/** 新增字典 */
export function createDictType(data: SystemDictTypeApi.DictType) {
  return requestClient.post('/system/dict-type/create', data);
}

/** 修改字典 */
export function updateDictType(data: SystemDictTypeApi.DictType) {
  return requestClient.put('/system/dict-type/update', data);
}

/** 删除字典 */
export function deleteDictType(id: number) {
  return requestClient.delete(`/system/dict-type/delete?id=${id}`);
}
