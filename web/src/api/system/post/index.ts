import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemPostApi {
  export interface Post {
    id?: number;
    code: string;
    name: string;
    sort: number;
    status: number;
    remark: string;
    createTime?: string;
  }
}

/** 查询岗位列表 */
export function getPostPage(params: PageParam) {
  return requestClient.get<PageResult<SystemPostApi.Post>>('/system/post/page', { params });
}

/** 查询岗位（精简)列表 */
export function getSimplePostList() {
  return requestClient.get<SystemPostApi.Post[]>('/system/post/simple-list');
}

/** 查询岗位详情 */
export function getPost(id: number) {
  return requestClient.get<SystemPostApi.Post>(`/system/post/get?id=${id}`);
}

/** 新增岗位 */
export function createPost(data: SystemPostApi.Post) {
  return requestClient.post('/system/post/create', data);
}

/** 修改岗位 */
export function updatePost(data: SystemPostApi.Post) {
  return requestClient.put('/system/post/update', data);
}

/** 删除岗位 */
export function deletePost(id: number) {
  return requestClient.delete(`/system/post/delete?id=${id}`);
}
