import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

/**
 * 闲鱼消息工作台 API（02 号文档 §5.1）
 * 消息分页 + 会话聚合列表（未绑定账号按昵称快照独立成会话）。
 */
export namespace XianyuMessageApi {
  export interface ChatMessage {
    id?: number;
    /** 闲鱼账号 ID（未对齐为 null） */
    accountId?: number | null;
    /** 账号昵称快照（未绑定账号的唯一标识） */
    accountNick?: string;
    buyerNick?: string;
    /** 会话绑定闲鱼商品 ID（可空=未关联商品会话） */
    itemId?: number | null;
    /** in（收到）/ out（发出） */
    direction?: string;
    /** text / image / emoji / audio / video / order / card / other */
    contentType?: string;
    content?: string;
    /** 结构化快照 JSON（ProductExt / MediaExt）；媒体资源仅从校验后的 MediaExt 读取 */
    extJson?: string;
    /** 闲鱼侧消息时间（OCR 估算） */
    receiveTime?: string;
    /** unread / read / replied */
    msgStatus?: string;
    /** 关联回复任务 ID（仅 out 消息；前端据此把任务状态回填到气泡） */
    taskId?: number | null;
    /** 发送状态（仅 out）：sending / sent / failed / unknown；null=存量已发出消息（按已发送静默展示） */
    sendStatus?: string | null;
    /** 发送失败/结果未知原因（仅 out，sendStatus=failed/unknown 时有值） */
    failReason?: string | null;
    createTime?: string;
  }

  /** 商品卡 ext 快照（协议键名契约：type/itemId/title/price/delivery/imageUrl/url，13 号 §4.2） */
  export interface ProductExt {
    type?: string;
    itemId?: string;
    /** 标题可空（卡片 DOM 无标题文本，13 号 §2.2） */
    title?: string;
    price?: string;
    delivery?: string;
    imageUrl?: string;
    url?: string;
  }

  export type MediaType = 'image' | 'emoji' | 'audio' | 'video';

  /** 媒体资源快照；URL 必须通过运行时校验后才能用于 src / href / poster。 */
  export interface MediaItem {
    type: MediaType;
    url?: string;
    posterUrl?: string;
    alt?: string;
    /** 时长，单位秒 */
    duration?: number;
    unavailableReason?: string;
  }

  /** 复用 ext_json，无新增接口；解析时需验证结构，最多接受 20 个资源。 */
  export interface MediaExt {
    type: 'media';
    sourceMessageId?: string;
    items: MediaItem[];
    dedupKey?: string;
  }

  export interface MessagePageReqVO extends PageParam {
    agentId?: number;
    accountId?: number;
    /** 账号昵称快照，模糊匹配 */
    accountNick?: string;
    /** 买家昵称，模糊匹配 */
    buyerNick?: string;
    /** unread / read / replied */
    msgStatus?: string;
    /** in / out */
    direction?: string;
    /** 会话绑定闲鱼商品 ID（string，非空时精确匹配 item_id，itemUnbound 忽略） */
    itemId?: string;
    /** 匹配未关联商品会话（item_id IS NULL）；itemId 为空时生效 */
    itemUnbound?: boolean;
  }

  export interface Conversation {
    /** 闲鱼账号 ID（未绑定会话为 null，前端显示「未绑定账号 + 快照昵称」告警） */
    accountId?: number | null;
    /** 账号昵称快照（未绑定会话的唯一标识） */
    accountNick?: string;
    /** 买家昵称（会话键） */
    buyerNick?: string;
    /** 会话唯一标识（= 闲鱼会话 sessionId，即落库 chat_id；null=无会话 id，前端按 buyerNick 兜底） */
    chatId?: string | null;
    /** 会话绑定闲鱼商品 ID（可空=未关联商品会话；聚合键维度） */
    itemId?: number | null;
    /** 绑定商品标题（item_id 命中商品表回填；null 时前端兜底「商品#itemId」） */
    productTitle?: string | null;
    /** 绑定商品价格（分；item_id 命中商品表回填） */
    productPrice?: number | null;
    /** 绑定商品主图 URL（item_id 命中商品表回填；未命中时兜底取最近商品卡 ext 的 imageUrl） */
    productImageUrl?: string | null;
    /** 绑定商品展示价（商品表未命中时取最近商品卡 ext 的展示价串，如 ¥1.00；命中商品表为 null，前端用 productPrice 分转元） */
    productPriceText?: string | null;
    /** 绑定商品详情页链接（商品表未命中时取最近商品卡 ext 的 url，供头部「查看商品」） */
    productUrl?: string | null;
    lastMessageContent?: string;
    /** 最后一条消息类型（card 内容常空，前端据此显示「[商品卡]」占位） */
    lastMessageContentType?: string;
    /** in / out */
    lastMessageDirection?: string;
    lastMessageTime?: string;
    unreadCount?: number;
  }

  export interface ConversationListReqVO {
    accountId?: number;
    accountNick?: string;
  }

  export interface ConversationReadReqVO {
    /** 闲鱼账号 ID（未对齐会话传空，用 accountNick 兜底） */
    accountId?: number;
    /** 账号昵称快照（accountId 为空时的会话定位键） */
    accountNick?: string;
    /** 买家昵称（会话键，必填） */
    buyerNick: string;
    /** 会话唯一标识（= 落库 chat_id；传空时按 buyerNick 兜底定位） */
    chatId?: string | null;
    /** 会话绑定闲鱼商品 ID（可空；NULL 匹配 NULL，防跨商品会话红点串扰） */
    itemId?: number | null;
  }
}

export function isXianyuMediaType(value: unknown): value is XianyuMessageApi.MediaType {
  return value === 'image' || value === 'emoji' || value === 'audio' || value === 'video';
}

export const getXianyuMessagePage = (params: XianyuMessageApi.MessagePageReqVO) =>
  requestClient.get<PageResult<XianyuMessageApi.ChatMessage>>('/app/xianyu/message/page', { params });

export const getXianyuConversationList = (params: XianyuMessageApi.ConversationListReqVO) =>
  requestClient.get<XianyuMessageApi.Conversation[]>('/app/xianyu/message/conversation-list', { params });

/** 会话标记已读（打开会话时调用），返回置为已读的消息行数 */
export const markXianyuConversationRead = (data: XianyuMessageApi.ConversationReadReqVO) =>
  requestClient.put<number>('/app/xianyu/message/read', data);

/**
 * 全局未读消息总数（14 号 §5.1：轻量 COUNT，口径与会话未读聚合一致，
 * 保证总数 = 工作台左栏各会话徽标之和）。
 * 静默请求：供全局提醒 10s 轮询使用，错误由 reminder store 自行消化（不弹全局提示）。
 */
export const getXianyuUnreadCount = () =>
  requestClient.get<number>('/app/xianyu/message/unread-count', { __silent: true });
