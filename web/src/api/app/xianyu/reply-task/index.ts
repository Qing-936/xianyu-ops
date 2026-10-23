import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

/**
 * 闲鱼回复任务 API（02 号文档 §5.1）——唯一外发通道
 * 创建时后端做外发闸门校验：账号绑定 → Agent 在线 → 账号登录 → 内容非空/长度。
 */
export namespace XianyuReplyTaskApi {
  export interface ReplyTask {
    id?: number;
    agentId?: number;
    accountId?: number;
    buyerNick?: string;
    /** 会话绑定闲鱼商品 ID（可空=未绑定商品会话；回复定位弱校验入参） */
    itemId?: number | null;
    /** 会话唯一标识（= 会话 sessionId，可空；out 消息落库 convId 与 in 同源） */
    chatId?: string | null;
    content?: string;
    /** text / image / emoji */
    contentType?: string;
    /** manual / ai */
    source?: string;
    /** pending / dispatched / success / failed / canceled / timeout */
    status?: string;
    /** 执行结果说明（失败原因） */
    resultMsg?: string;
    /** 发送后验证截图 URL（OSS） */
    verifyImageUrl?: string;
    createdBy?: number;
    createTime?: string;
    /** 下发时间（Agent 拉取） */
    dispatchedAt?: string;
    /** 完成时间 */
    finishedAt?: string;
  }

  export interface CreateReqVO {
    /** 目标闲鱼账号 ID */
    accountId: number;
    /** 目标买家昵称（会话键） */
    buyerNick: string;
    /** 回复哪条消息（可空 = 主动发起会话） */
    messageId?: number;
    /** 会话绑定闲鱼商品 ID（可空 = 未绑定商品会话不传） */
    itemId?: number | null;
    /** 会话唯一标识（= 会话 sessionId，可空）——必传当前会话 chatId，否则 out 消息会话分裂 */
    chatId?: string | null;
    /** 内容类型：text/image/emoji（缺省 text） */
    contentType?: 'text' | 'image' | 'emoji';
    /** 媒体内容（image 需 url；emoji 需 alt） */
    media?: {
      type: 'image' | 'emoji';
      url?: string;
      alt?: string;
    };
    /** 回复内容（text 必填；媒体可空，服务端补占位） */
    content?: string;
  }

  /** 表情目录项（GET /app/xianyu/reply-task/emoji-catalog） */
  export interface EmojiCatalogItem {
    name?: string;
    alt: string;
    url: string;
  }

  export interface TaskPageReqVO extends PageParam {
    /** pending / dispatched / success / failed / canceled / timeout */
    status?: string;
    agentId?: number;
    accountId?: number;
  }
}

export const createXianyuReplyTask = (data: XianyuReplyTaskApi.CreateReqVO) =>
  requestClient.post<number>('/app/xianyu/reply-task/create', data);

export const cancelXianyuReplyTask = (taskId: number) =>
  requestClient.post<boolean>(`/app/xianyu/reply-task/${taskId}/cancel`);

/** 查询单个回复任务（发送状态轮询：比拉整页更精准，支持同会话并发多任务） */
export const getXianyuReplyTask = (taskId: number) =>
  requestClient.get<XianyuReplyTaskApi.ReplyTask>(`/app/xianyu/reply-task/${taskId}`);

/** 重试失败任务：后端复制原任务重过外发闼门，并复用原消息气泡，返回新任务 ID */
export const retryXianyuReplyTask = (taskId: number) =>
  requestClient.post<number>(`/app/xianyu/reply-task/${taskId}/retry`);

export const getXianyuReplyTaskPage = (params: XianyuReplyTaskApi.TaskPageReqVO) =>
  requestClient.get<PageResult<XianyuReplyTaskApi.ReplyTask>>('/app/xianyu/reply-task/page', { params });

/** 表情目录（管理端选择器；alt 白名单与后端创建校验同源） */
export const getXianyuEmojiCatalog = () =>
  requestClient.get<XianyuReplyTaskApi.EmojiCatalogItem[]>('/app/xianyu/reply-task/emoji-catalog');
