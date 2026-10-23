<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue';
import { Card, Input, Button, Tag, Badge, Empty, message, Modal, Spin, Tooltip, Alert, Popover } from 'ant-design-vue';
import {
  ReloadOutlined, SearchOutlined, SendOutlined, UserOutlined, ShopOutlined,
  PictureOutlined, FileTextOutlined, ShoppingCartOutlined, CreditCardOutlined,
  LinkOutlined, SmileOutlined, AudioOutlined, VideoCameraOutlined,
} from '@ant-design/icons-vue';
import { getXianyuConversationList, getXianyuMessagePage, markXianyuConversationRead, isXianyuMediaType } from '#/api/app/xianyu/message';
import type { XianyuMessageApi } from '#/api/app/xianyu/message';
import MediaMessage from './components/MediaMessage.vue';
import {
  createXianyuReplyTask,
  getXianyuEmojiCatalog,
  getXianyuReplyTask,
  retryXianyuReplyTask,
} from '#/api/app/xianyu/reply-task';
import type { XianyuReplyTaskApi } from '#/api/app/xianyu/reply-task';
import { uploadFile } from '#/api/infra/file';
import { getXianyuMachineAgentPage } from '#/api/app/xianyu/agent';
import { formatDateTime } from '#/utils/format';
import { useReminderStore } from '#/store/reminder';
import dayjs from 'dayjs';

defineOptions({ name: 'AppXianyuMessage' });

/** 全局提醒 store（14 号 §5.6：标记已读后即时回写全局未读数） */
const reminderStore = useReminderStore();

// ====== 顶部：Agent 在线状态横幅（01 §6.2） ======
const onlineAgentCount = ref(0);
const agentTotal = ref(0);

async function loadAgentStatus() {
  try {
    const res = await getXianyuMachineAgentPage({ pageNo: 1, pageSize: 100 });
    agentTotal.value = res.total || 0;
    onlineAgentCount.value = (res.list || []).filter((a) => a.status === 'online').length;
  } catch {
    // 查询失败保持上次状态
  }
}

const bannerType = computed<'success' | 'warning' | 'info'>(() => {
  if (agentTotal.value === 0) return 'info';
  return onlineAgentCount.value > 0 ? 'success' : 'warning';
});

const bannerText = computed(() => {
  if (agentTotal.value === 0) return '暂无 Agent 接入，买家消息无法上报。';
  if (onlineAgentCount.value === 0) return '所有 Agent 均已离线，回复任务将无法下发。';
  return `${onlineAgentCount.value} / ${agentTotal.value} 个 Agent 在线。`;
});

// ====== 左栏：会话列表 ======
const conversationLoading = ref(false);
const conversations = ref<XianyuMessageApi.Conversation[]>([]);
const convFilter = ref('');
const currentConversation = ref<XianyuMessageApi.Conversation | null>(null);

async function loadConversations() {
  conversationLoading.value = true;
  try {
    conversations.value = await getXianyuConversationList({
      accountNick: convFilter.value || undefined,
    });
    // 保持当前选中会话（按聚合键匹配，含 item 维度）
    if (currentConversation.value) {
      const cur = currentConversation.value;
      currentConversation.value = conversations.value.find((c) => convKey(c) === convKey(cur)) || null;
    }
  } finally {
    conversationLoading.value = false;
  }
}

/**
 * 会话聚合键：account + 会话唯一 id + item 三维（2026-09-24 会话唯一性修复）。
 * chatId 非空 → 按会话精确区分（同一买家对多个商品的会话不再被合并）；
 * 为空 → 退化为 'noncid:' + 买家昵称，与后端 convId 口径完全一致；
 * accountId 可能为 null 用 accountNick 兜底；itemId 可空=「未关联商品」会话。
 */
function convKey(c: XianyuMessageApi.Conversation): string {
  const agg = c.accountId ?? `unbound:${c.accountNick || ''}`;
  const convId = c.chatId ? c.chatId : `noncid:${c.buyerNick || ''}`;
  return `${agg}|${convId}|${c.itemId ?? 'unbound'}`;
}

/** 会话标题：买家昵称 + 账号昵称（未绑定账号黄色告警，01 §3.6） */
function convTitle(c: XianyuMessageApi.Conversation): string {
  return `${c.buyerNick} · ${c.accountId != null ? c.accountNick || `账号#${c.accountId}` : `未绑定账号 ${c.accountNick || ''}`}`;
}

/** 媒体 caption 排除默认占位、误入 content 的原始 JSON / 纯 URL；摘要不展示资源地址。 */
function mediaCaption(content?: string, summary = false): string {
  if (typeof content !== 'string') return '';
  const text = content.trim().replace(/^(?:\[(?:图片|表情|语音|视频)\]\s*)+/u, '').trim();
  if (!text) return '';
  if (/^[\[{]/u.test(text)) {
    try {
      const value: unknown = JSON.parse(text);
      if (typeof value === 'object' && value !== null) return '';
    } catch {
      // 坏 JSON 同样不外露；保留 [微笑] 等正常文字表情。
      if (/^(?:\{|\[\s*[\[{"])/u.test(text)) return '';
    }
  }
  const urlPattern = /(?:[a-z][a-z\d+.-]*:\/\/|(?:javascript|data|blob|file):|\/\/)[^\s<>"'，。！？；）】]+/gi;
  if (!text.replace(urlPattern, '').trim()) return '';
  return summary ? text.replace(urlPattern, '[链接]') : text;
}

/** 媒体类型优先：始终保留中文标签；文本和商品卡摘要维持原行为。 */
function convSummary(c: XianyuMessageApi.Conversation): string {
  if (isXianyuMediaType(c.lastMessageContentType)) {
    const caption = mediaCaption(c.lastMessageContent, true);
    return `${CONTENT_TYPE_TEXT[c.lastMessageContentType]}${caption ? ` ${caption}` : ''}`;
  }
  if (c.lastMessageContent) return c.lastMessageContent;
  return c.lastMessageContentType === 'card' ? '[商品卡]' : '';
}

/**
 * 会话是否已有可展示的商品信息（方案 A，2026-09-24）：商品目录命中、商品卡 ext 兜底、
 * 或会话级商品快照（行缩略图/最近卡片价格文本）任一存在即可渲染商品区块——
 * 含 itemId 为 null 的「未关联商品」会话（仅有图/价）。
 */
function hasProduct(c: XianyuMessageApi.Conversation): boolean {
  return c.itemId != null
    || !!c.productTitle
    || !!c.productImageUrl
    || !!c.productPriceText
    || c.productPrice != null;
}

/** 会话商品标题：优先商品表标题；无标题但已绑定 itemId 时兜底「商品#itemId」；均无则空（不渲染） */
function productLabel(c: XianyuMessageApi.Conversation): string {
  if (c.productTitle) return c.productTitle;
  return c.itemId != null ? `商品#${c.itemId}` : '';
}

function shortTime(time?: string): string {
  if (!time) return '-';
  const d = dayjs(time);
  if (!d.isValid()) return '-';
  // 今天的消息只显示时分，跨天带月日
  if (d.isSame(dayjs(), 'day')) return d.format('HH:mm');
  return d.format('MM-DD HH:mm');
}

function selectConversation(c: XianyuMessageApi.Conversation) {
  currentConversation.value = c;
  messagePageNo.value = 1;
  messages.value = [];
  hasMore.value = false;
  // 切换会话：清空未发送的图片/表情草稿，避免串到另一个买家
  pendingImage.value = null;
  pendingEmoji.value = null;
  // 切换会话：停止上一会话的发送状态轮询
  // （状态存于消息行 send_status，新会话若仍有在途消息，loadMessages 后会自动恢复轮询）
  stopSendPolling();
  loadMessages();
}

// ====== 中栏：消息流 ======
const messageLoading = ref(false);
const messages = ref<XianyuMessageApi.ChatMessage[]>([]);
const messagePageNo = ref(1);
const hasMore = ref(false);
const messagesEl = ref<HTMLElement>();

const MESSAGE_PAGE_SIZE = 30;

async function loadMessages(appendOlder = false) {
  const conv = currentConversation.value;
  if (!conv) return;
  messageLoading.value = true;
  try {
    const params: XianyuMessageApi.MessagePageReqVO = {
      pageNo: appendOlder ? messagePageNo.value : 1,
      pageSize: MESSAGE_PAGE_SIZE,
      buyerNick: conv.buyerNick,
    };
    // 未绑定会话无 accountId，用账号昵称快照筛选（02 §5.2）
    if (conv.accountId != null) {
      params.accountId = conv.accountId;
    } else {
      params.accountNick = conv.accountNick;
    }
    // 消息流按会话 item 维度隔离（13 号 §4.4 契约：必须总传其一）：
    // 绑定会话传 itemId 精确匹配；未关联商品会话传 itemUnbound=true 匹配 item_id IS NULL。
    if (conv.itemId != null) {
      params.itemId = String(conv.itemId);
    } else {
      params.itemUnbound = true;
    }
    const res = await getXianyuMessagePage(params);
    // 后端按时间倒序返回（最新在前），页面从上到下旧→新，反转后拼接
    const pageList = (res.list || []).slice().reverse();
    if (appendOlder) {
      messages.value = [...pageList, ...messages.value];
    } else {
      messages.value = pageList;
    }
    hasMore.value = (res.total || 0) > messagePageNo.value * MESSAGE_PAGE_SIZE;
  } finally {
    messageLoading.value = false;
    if (!appendOlder) {
      scrollToBottom();
      // 打开/刷新会话后：若仍有未读则标记已读，消除左栏红点
      void maybeMarkRead();
      // 会话内若仍有「发送中」的 out 消息，恢复发送状态轮询（切会话/刷新不丢跟踪）
      if (
        messages.value.some(
          (m) => m.direction === 'out' && m.sendStatus === 'sending' && m.taskId != null,
        )
      ) {
        startSendPolling();
      }
    }
  }
}

async function loadOlderMessages() {
  messagePageNo.value += 1;
  await loadMessages(true);
}

/** 标记已读防重入（10s 定时刷新与首屏加载可能并发触发） */
let markingRead = false;

/**
 * 打开/刷新会话后标记已读：
 * 后端原无「已读」写入（历史缺陷），红点由未读数聚合而来，需前端显式回写。
 * 标记成功后本地即时置 read（避免下一次刷新前红点残留）并刷新会话列表。
 */
async function maybeMarkRead() {
  const conv = currentConversation.value;
  if (!conv || markingRead) return;
  const hasUnread =
    (conv.unreadCount || 0) > 0 ||
    messages.value.some((m) => m.direction === 'in' && m.msgStatus === 'unread');
  if (!hasUnread) return;
  markingRead = true;
  try {
    const params: XianyuMessageApi.ConversationReadReqVO = {
      buyerNick: conv.buyerNick || '',
      // 会话唯一 id + item 维度随会话传（NULL 匹配 NULL）：
      // 打开同一买家的 A 商品会话不得清掉 B 商品会话红点（2026-09-24 会话唯一性）
      chatId: conv.chatId ?? null,
      itemId: conv.itemId ?? null,
    };
    if (conv.accountId != null) {
      params.accountId = conv.accountId;
    } else {
      params.accountNick = conv.accountNick;
    }
    await markXianyuConversationRead(params);
    messages.value.forEach((m) => {
      if (m.direction === 'in' && m.msgStatus === 'unread') m.msgStatus = 'read';
    });
    await loadConversations();
    syncReminderCount();
  } catch {
    // 标记已读失败不阻断消息展示，下次刷新重试
  } finally {
    markingRead = false;
  }
}

/**
 * 会话列表未读求和回写全局提醒（14 号 §5.6）：
 * 用户读完会话后全局徽标/标题立即回落，不必等下一轮 10s 轮询；
 * 口径 = 后端 COUNT(*)（未读行数），全量列表求和天然等于全局总数。
 * 仅在未启用账号筛选时回写——筛选后列表是子集，求和会低估全局数。
 */
function syncReminderCount() {
  if (convFilter.value) return;
  const sum = conversations.value.reduce((acc, c) => acc + (c.unreadCount || 0), 0);
  reminderStore.setCount('xianyu-message', sum);
}

async function scrollToBottom() {
  await nextTick();
  if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
}

const CONTENT_TYPE_ICON: Record<string, unknown> = {
  image: PictureOutlined,
  emoji: SmileOutlined,
  audio: AudioOutlined,
  video: VideoCameraOutlined,
  order: ShoppingCartOutlined,
  card: CreditCardOutlined,
  other: FileTextOutlined,
};

const CONTENT_TYPE_TEXT: Record<string, string> = {
  text: '',
  image: '[图片]',
  emoji: '[表情]',
  audio: '[语音]',
  video: '[视频]',
  order: '[订单]',
  card: '[卡片]',
  other: '[其他]',
};

// ====== 商品卡渲染（13 号 §4.2 ext 契约：type/itemId/title/price/delivery/imageUrl/url；§4.5 extJson 唯一数据源） ======

/** 解析消息 extJson 为商品卡快照；非商品卡 / 解析失败 / 为空 → null（降级 [卡片] 占位，13 号 §4.5） */
function parseCardExt(m: XianyuMessageApi.ChatMessage): XianyuMessageApi.ProductExt | null {
  if (!m.extJson) return null;
  try {
    const ext = JSON.parse(m.extJson) as XianyuMessageApi.ProductExt;
    return ext && ext.type === 'product' ? ext : null;
  } catch {
    return null;
  }
}

/** 消息 → 商品卡快照缓存（模板零重复 JSON.parse；仅对 card 消息解析） */
const cardExtMap = computed(() => {
  const map = new Map<number, XianyuMessageApi.ProductExt | null>();
  for (const m of messages.value) {
    if (m.contentType === 'card') map.set(m.id ?? -1, parseCardExt(m));
  }
  return map;
});

function cardExtOf(m: XianyuMessageApi.ChatMessage): XianyuMessageApi.ProductExt | null {
  return cardExtMap.value.get(m.id ?? -1) ?? null;
}

/** 卡片标题兜底链（13 号 §2.2 结论 2）：会话头部商品表标题 → ext.title → 商品#itemId */
function cardTitle(ext: XianyuMessageApi.ProductExt): string {
  const conv = currentConversation.value;
  if (conv?.productTitle && conv.itemId != null && String(conv.itemId) === String(ext.itemId || '')) {
    return conv.productTitle;
  }
  return (ext.title || '').trim() || (ext.itemId ? `商品#${ext.itemId}` : '商品');
}

/** 「查看商品」外链：ext.url 绝对地址直接用，相对路径补 goofish 域名，否则按 itemId 拼 */
function cardLink(ext: XianyuMessageApi.ProductExt): string {
  if (ext.url) {
    if (/^https?:\/\//.test(ext.url)) return ext.url;
    return `https://www.goofish.com${ext.url.startsWith('/') ? '' : '/'}${ext.url}`;
  }
  return `https://www.goofish.com/item?id=${ext.itemId || ''}`;
}

/** 分 → 元字符串（商品表 productPrice 单位为分；null 返回空） */
function formatFen(price?: number | null): string {
  return price == null ? '' : (price / 100).toFixed(2);
}

/** 回复某条消息：回复任务 messageId 取会话中最后一条「收到」的消息 */
const replyMessageId = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].direction === 'in') return messages.value[i].id;
  }
  return undefined;
});

// ====== 底部：回复发送 + 消息发送状态跟踪 ======
const replyContent = ref('');
const sending = ref(false);
/** 正在重试的消息 id（控制「重试」按钮 loading，同时防重复点击） */
const retryingId = ref<number | null>(null);
let sendPollTimer: ReturnType<typeof setInterval> | undefined;

// 图片/表情待发送草稿（v1 只能选一种媒体；文本与媒体互斥）
const pendingImage = ref<{ url: string; name: string } | null>(null);
const pendingEmoji = ref<XianyuReplyTaskApi.EmojiCatalogItem | null>(null);
const imageInput = ref<HTMLInputElement>();
const imageUploading = ref(false);
const emojiOpen = ref(false);
const emojiCatalog = ref<XianyuReplyTaskApi.EmojiCatalogItem[]>([]);
const emojiLoading = ref(false);

/** 手动改写文本后，如果不再是所选表情 token，则退出表情任务（按普通文本发送） */
watch(replyContent, (value) => {
  if (pendingEmoji.value && value.trim() !== pendingEmoji.value.alt) {
    pendingEmoji.value = null;
  }
});

/** 未绑定会话不可回复（外发闸门要求 accountId 必填） */
const replyDisabled = computed(() => !currentConversation.value || currentConversation.value.accountId == null);

const canSend = computed(
  () =>
    !replyDisabled.value &&
    !sending.value &&
    !imageUploading.value &&
    (replyContent.value.trim().length > 0 || !!pendingImage.value || !!pendingEmoji.value),
);

const replyPlaceholder = computed(() => {
  if (replyDisabled.value) return '该会话账号未绑定主数据（accountId 为空），外发闸门禁止回复';
  if (imageUploading.value) return '图片上传中…';
  if (pendingImage.value) return '图片待发送，点击发送；可重新选择替换';
  if (pendingEmoji.value) return '表情待发送，点击发送';
  return '输入回复内容，Enter 发送，Shift + Enter 换行';
});

/** 本会话是否存在「发送中」消息（仅驱动底部轻提示；气泡状态本身由消息行 send_status 渲染） */
const hasSendingMessage = computed(() =>
  messages.value.some((m) => m.direction === 'out' && m.sendStatus === 'sending'),
);

function triggerImagePick() {
  if (replyDisabled.value || sending.value || imageUploading.value) return;
  imageInput.value?.click();
}

/** 选择本地图片：前端按客户端同口径预检（<3MB，JPG/PNG/GIF/BMP），再上传 OSS 待发 */
async function handleImageSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  const allowed = ['image/jpeg', 'image/png', 'image/gif', 'image/bmp'];
  if (!allowed.includes(file.type)) {
    message.warning('仅支持 JPG/PNG/GIF/BMP 图片');
    return;
  }
  if (file.size >= 3 * 1024 * 1024) {
    message.warning('图片必须小于 3MB');
    return;
  }
  imageUploading.value = true;
  try {
    const url = await uploadFile({ file, directory: 'xianyu/reply' });
    pendingEmoji.value = null;
    pendingImage.value = { url, name: file.name };
    replyContent.value = '';
  } catch {
    message.error('图片上传失败，请重试');
  } finally {
    imageUploading.value = false;
  }
}

function clearPendingImage() {
  pendingImage.value = null;
}

function clearPendingEmoji() {
  pendingEmoji.value = null;
  replyContent.value = '';
}

async function loadEmojiCatalog() {
  if (emojiCatalog.value.length || emojiLoading.value) return;
  emojiLoading.value = true;
  try {
    emojiCatalog.value = (await getXianyuEmojiCatalog()) || [];
  } catch {
    message.error('表情目录加载失败');
  } finally {
    emojiLoading.value = false;
  }
}

function handleEmojiOpenChange(open: boolean) {
  emojiOpen.value = open;
  if (open) void loadEmojiCatalog();
}

function selectEmoji(item: XianyuReplyTaskApi.EmojiCatalogItem) {
  pendingImage.value = null;
  pendingEmoji.value = item;
  replyContent.value = item.alt;
  emojiOpen.value = false;
}

async function handleSendReply() {
  const conv = currentConversation.value;
  if (!conv || replyDisabled.value || sending.value || imageUploading.value) return;
  const text = replyContent.value.trim();
  const image = pendingImage.value;
  const emoji = pendingEmoji.value && text === pendingEmoji.value.alt ? pendingEmoji.value : null;
  if (!text && !image) {
    message.warning('请输入回复内容或选择图片/表情');
    return;
  }
  const contentType = image ? 'image' : emoji ? 'emoji' : 'text';
  const media = image
    ? { type: 'image' as const, url: image.url }
    : emoji
      ? { type: 'emoji' as const, alt: emoji.alt, url: emoji.url }
      : undefined;
  sending.value = true;
  try {
    const taskId = await createXianyuReplyTask({
      accountId: conv.accountId!,
      // buyerNick 是会话键，正常必存在；类型可选兜底空串（后端 @NotBlank 会拦截）
      buyerNick: conv.buyerNick || '',
      messageId: replyMessageId.value,
      // 会话绑定商品 ID 随任务下发（回复定位弱校验入参）；未关联商品会话不传（13 号 §4.3）
      itemId: conv.itemId ?? null,
      // 会话唯一 id：后端据此给 out 消息落库同源 convId，防同买家多会话分裂
      chatId: conv.chatId ?? null,
      contentType,
      media,
      // 媒体任务 content 可空，后端补 [图片]/[微笑] 占位
      content: image ? '' : text,
    });
    replyContent.value = '';
    pendingImage.value = null;
    pendingEmoji.value = null;
    // 发送即落库：立即刷新消息流，让「发送中」气泡出现在聊天历史（而非独立状态条）
    await loadMessages();
    startSendPolling();
    const label = contentType === 'image' ? '图片' : contentType === 'emoji' ? '表情' : '回复';
    message.success(`${label}任务已创建（#${taskId}），等待 Agent 发送`);
  } catch {
    // 后端已弹出闸门失败原因
  } finally {
    sending.value = false;
  }
}

/**
 * 发送状态轮询上限：服务端回复任务超时兜底为 5 分钟（XianyuAgentProperties.replyTaskTimeoutMinutes），
 * 轮询时长须覆盖该阈值，否则任务完成前轮询已放弃、气泡永远停在「发送中」
 * （实测任务耗时 139s 即超过旧上限 5s×24=120s）。5s × 120 = 10 分钟，留足余量。
 */
const MAX_SEND_POLL_ATTEMPTS = 120;

/** 回复任务终态：到达后本会话无需继续轮询 */
const TERMINAL_TASK_STATUS = ['success', 'failed', 'canceled', 'timeout'];

/**
 * 轮询本会话内「发送中」消息对应的任务状态：
 * 逐条按 taskId 单查（替代旧版按账号分页找任务），任一进入终态即刷新消息流与会话列表。
 */
function startSendPolling() {
  // 幂等：已在轮询中不重启，否则 10s 消息刷新会反复复位 attempts 导致上限失效
  if (sendPollTimer) return;
  let attempts = 0;
  sendPollTimer = setInterval(async () => {
    attempts += 1;
    const pending = messages.value.filter(
      (m) => m.direction === 'out' && m.sendStatus === 'sending' && m.taskId != null,
    );
    if (!pending.length) {
      stopSendPolling();
      return;
    }
    let settled = false;
    await Promise.all(
      pending.map(async (m) => {
        try {
          const task = await getXianyuReplyTask(m.taskId!);
          if (TERMINAL_TASK_STATUS.includes(task.status || '')) settled = true;
        } catch {
          // 单条查询失败静默重试，不阻断其余消息
        }
      }),
    );
    if (settled || attempts >= MAX_SEND_POLL_ATTEMPTS) {
      stopSendPolling();
      if (currentConversation.value) {
        await loadMessages();
        await loadConversations();
      }
    }
  }, 5000);
}

function stopSendPolling() {
  if (sendPollTimer) {
    clearInterval(sendPollTimer);
    sendPollTimer = undefined;
  }
}

/**
 * 失败/未知消息重试：复用同一条气泡（后端重置 send_status 并改绑新 taskId）。
 * 结果未知时二次确认：Agent 未回传不代表未发出，重试可能让对方收到重复消息。
 */
function handleRetrySend(m: XianyuMessageApi.ChatMessage) {
  if (m.taskId == null || retryingId.value != null) return;
  if (m.sendStatus === 'unknown') {
    Modal.confirm({
      title: '该消息发送结果未知，确认重试？',
      content: 'Agent 未回传结果，消息可能已实际发出。重试可能造成对方收到重复消息，建议先人工核实。',
      okText: '确认重试',
      cancelText: '取消',
      onOk: () => doRetrySend(m),
    });
    return;
  }
  void doRetrySend(m);
}

/** 实际重试：调用重试接口后刷新消息流并恢复轮询 */
async function doRetrySend(m: XianyuMessageApi.ChatMessage) {
  if (m.taskId == null) return;
  retryingId.value = m.id ?? null;
  try {
    await retryXianyuReplyTask(m.taskId);
    await loadMessages();
    startSendPolling();
  } catch {
    // 后端已弹出失败原因
  } finally {
    retryingId.value = null;
  }
}

/** 发送状态文案/颜色（send_status 口径，与后端 XianyuSendStatusEnum 对齐） */
const SEND_STATUS_TEXT: Record<string, string> = {
  sending: '发送中',
  sent: '已发送',
  failed: '发送失败',
  unknown: '结果未知',
};

const SEND_STATUS_COLOR: Record<string, string> = {
  sending: 'processing',
  sent: 'success',
  failed: 'error',
  unknown: 'warning',
};

/** 失败/未知原因缺失时的兜底文案 */
const SEND_FAIL_FALLBACK: Record<string, string> = {
  failed: '发送失败',
  unknown: 'Agent 未回传结果，请人工核实',
};

function handleReplyKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    if (!sending.value) handleSendReply();
  }
}

// ====== 定时刷新与生命周期 ======
let convTimer: ReturnType<typeof setInterval> | undefined;
let msgTimer: ReturnType<typeof setInterval> | undefined;
let agentTimer: ReturnType<typeof setInterval> | undefined;

onMounted(() => {
  loadAgentStatus();
  loadConversations();
  // 会话列表 30s 刷新；选中会话消息流 10s 刷新；Agent 状态 30s 刷新
  convTimer = setInterval(loadConversations, 30_000);
  msgTimer = setInterval(() => {
    if (currentConversation.value && !sending.value) loadMessages();
  }, 10_000);
  agentTimer = setInterval(loadAgentStatus, 30_000);
});

onUnmounted(() => {
  stopSendPolling();
  if (convTimer) clearInterval(convTimer);
  if (msgTimer) clearInterval(msgTimer);
  if (agentTimer) clearInterval(agentTimer);
});
</script>

<template>
  <Card title="消息工作台" :bordered="false" class="workbench-card">
    <!-- 顶部：Agent 在线状态横幅 -->
    <Alert :type="bannerType" :message="bannerText" show-icon class="status-banner" />

    <div class="workbench-body">
      <!-- 左栏：会话列表 -->
      <div class="conv-pane">
        <div class="conv-filter">
          <Input
            v-model:value="convFilter"
            placeholder="账号昵称筛选"
            allow-clear
            size="small"
            @press-enter="loadConversations"
          />
          <Button size="small" @click="loadConversations"><SearchOutlined /></Button>
          <Button size="small" @click="loadConversations"><ReloadOutlined /></Button>
        </div>
        <div class="conv-list" :class="{ loading: conversationLoading }">
          <div
            v-for="c in conversations"
            :key="convKey(c)"
            class="conv-item"
            :class="{ active: currentConversation && convKey(currentConversation) === convKey(c) }"
            @click="selectConversation(c)"
          >
            <div class="conv-top">
              <span class="conv-buyer">{{ c.buyerNick }}</span>
              <Badge v-if="(c.unreadCount || 0) > 0" :count="c.unreadCount" />
              <span class="conv-time">{{ shortTime(c.lastMessageTime) }}</span>
            </div>
            <div class="conv-account">
              <ShopOutlined class="conv-account-icon" />
              <span v-if="c.accountId != null">{{ c.accountNick || `账号#${c.accountId}` }}</span>
              <span v-else class="unbound-warn">未绑定账号 {{ c.accountNick || '' }}</span>
            </div>
            <!-- 会话绑定商品：缩略图 + 标题 + 价格；无任何商品信息才显示「未关联商品」（13 号 §八.1）
                 方案 A：目录/商品卡 ext/会话级快照（行缩略图 + 最近卡片价格文本）任一命中即渲染，
                 itemId 为 null 的未关联会话也能显示缩略图/价格 -->
            <div class="conv-product">
              <template v-if="hasProduct(c)">
                <img v-if="c.productImageUrl" :src="c.productImageUrl" class="conv-product-img" alt="" />
                <PictureOutlined v-else class="conv-product-img-fallback" />
                <span v-if="productLabel(c)" class="conv-product-title">{{ productLabel(c) }}</span>
                <span v-if="c.productPriceText || c.productPrice != null" class="conv-product-price">
                  {{ c.productPriceText || ('¥' + formatFen(c.productPrice!)) }}
                </span>
              </template>
              <span v-else class="conv-unbound-product">未关联商品</span>
            </div>
            <div class="conv-summary">
              <span v-if="c.lastMessageDirection === 'out'" class="summary-out">我：</span>
              {{ convSummary(c) }}
            </div>
          </div>
          <Empty
            v-if="!conversationLoading && !conversations.length"
            :image="Empty.PRESENTED_IMAGE_SIMPLE"
            description="暂无会话"
            class="conv-empty"
          />
        </div>
      </div>

      <!-- 中栏：消息流 + 回复 -->
      <div class="chat-pane">
        <template v-if="currentConversation">
          <div class="chat-header">
            <span class="chat-title">{{ convTitle(currentConversation) }}</span>
            <div class="chat-header-right">
              <!-- 会话头部绑定商品卡：标题/价格/图；无任何商品信息才显示「未关联商品」（13 号 §八.4）
                   方案 A：会话级快照（行缩略图 + 最近商品卡价格文本）命中即渲染，未关联 itemId 也能展示 -->
              <div v-if="hasProduct(currentConversation)" class="chat-product">
                <img v-if="currentConversation.productImageUrl" :src="currentConversation.productImageUrl" class="chat-product-img" alt="" />
                <PictureOutlined v-else class="chat-product-img-fallback" />
                <span v-if="productLabel(currentConversation)" class="chat-product-title">{{ productLabel(currentConversation) }}</span>
                <span v-if="currentConversation.productPriceText || currentConversation.productPrice != null" class="chat-product-price">
                  {{ currentConversation.productPriceText || ('¥' + formatFen(currentConversation.productPrice!)) }}
                </span>
                <a
                  v-if="currentConversation.productUrl"
                  :href="currentConversation.productUrl"
                  target="_blank"
                  rel="noopener"
                  class="chat-product-link"
                >
                  <LinkOutlined /> 查看商品
                </a>
              </div>
              <span v-else class="chat-unbound-product">未关联商品</span>
              <span class="chat-hint">{{ currentConversation.accountId != null ? '' : '该会话账号未绑定主数据，无法回复' }}</span>
            </div>
          </div>

          <div ref="messagesEl" class="chat-messages">
            <div class="load-older">
              <Button
                v-if="hasMore"
                size="small"
                :loading="messageLoading"
                @click="loadOlderMessages"
              >加载更早消息</Button>
            </div>
            <div
              v-for="m in messages"
              :key="m.id"
              class="msg-row"
              :class="m.direction === 'out' ? 'msg-out' : 'msg-in'"
            >
              <div class="msg-avatar">
                <UserOutlined v-if="m.direction === 'in'" />
                <ShopOutlined v-else />
              </div>
              <div class="msg-body" :class="{ 'msg-body-media': isXianyuMediaType(m.contentType) }">
                <div class="msg-meta">
                  <span class="msg-nick">{{ m.direction === 'out' ? '我方' : m.buyerNick }}</span>
                  <span class="msg-time">{{ formatDateTime(m.receiveTime) }}</span>
                  <Tag v-if="m.direction === 'in' && m.msgStatus === 'replied'" color="green" class="msg-replied">已回复</Tag>
                  <Tag
                    v-if="m.direction === 'out' && m.sendStatus"
                    :color="SEND_STATUS_COLOR[m.sendStatus || ''] || 'default'"
                    class="msg-send-tag"
                  >{{ SEND_STATUS_TEXT[m.sendStatus || ''] || m.sendStatus }}</Tag>
                </div>
                <div
                  class="msg-bubble"
                  :class="{
                    'msg-bubble-card': m.contentType === 'card' && cardExtOf(m),
                    'msg-bubble-media': isXianyuMediaType(m.contentType),
                  }"
                >
                  <!-- 商品卡：extJson 快照渲染（主图/标题/价格/运费/查看商品外链）；空快照降级 [卡片] 占位（13 号 §4.5） -->
                  <div v-if="m.contentType === 'card' && cardExtOf(m)" class="product-card">
                    <div class="product-card-img-wrap">
                      <img v-if="cardExtOf(m)!.imageUrl" :src="cardExtOf(m)!.imageUrl" class="product-card-img" alt="" />
                      <PictureOutlined v-else class="product-card-img-fallback" />
                    </div>
                    <div class="product-card-info">
                      <div class="product-card-title">{{ cardTitle(cardExtOf(m)!) }}</div>
                      <div class="product-card-meta">
                        <span v-if="cardExtOf(m)!.price" class="product-card-price">{{ cardExtOf(m)!.price }}</span>
                        <span v-if="cardExtOf(m)!.delivery" class="product-card-delivery">{{ cardExtOf(m)!.delivery }}</span>
                      </div>
                      <a
                        :href="cardLink(cardExtOf(m)!)"
                        target="_blank"
                        rel="noopener"
                        class="product-card-link"
                      >
                        <LinkOutlined /> 查看商品
                      </a>
                    </div>
                  </div>
                  <MediaMessage
                    v-else-if="isXianyuMediaType(m.contentType)"
                    :ext-json="m.extJson"
                    :caption="m.direction === 'out' && m.contentType === 'emoji' ? '' : mediaCaption(m.content)"
                  >
                    <template #label>
                      <component :is="CONTENT_TYPE_ICON[m.contentType]" />
                      {{ CONTENT_TYPE_TEXT[m.contentType] }}
                    </template>
                  </MediaMessage>
                  <template v-else>
                    <span v-if="m.contentType && m.contentType !== 'text'" class="msg-ct-type">
                      <component :is="CONTENT_TYPE_ICON[m.contentType] || FileTextOutlined" />
                      {{ CONTENT_TYPE_TEXT[m.contentType] || `[${m.contentType}]` }}
                    </span>
                    {{ m.content }}
                  </template>
                </div>
                <!-- 发送失败/结果未知：失败原因 + 重试（复用同一条气泡） -->
                <div
                  v-if="m.direction === 'out' && (m.sendStatus === 'failed' || m.sendStatus === 'unknown')"
                  class="msg-send-failure"
                >
                  <span class="msg-fail-reason">{{ m.failReason || SEND_FAIL_FALLBACK[m.sendStatus || ''] || '' }}</span>
                  <Button
                    type="link"
                    size="small"
                    class="msg-retry-btn"
                    :loading="retryingId === m.id"
                    @click="handleRetrySend(m)"
                  >重试</Button>
                </div>
              </div>
            </div>
            <Empty
              v-if="!messageLoading && !messages.length"
              :image="Empty.PRESENTED_IMAGE_SIMPLE"
              description="该会话暂无消息"
            />
          </div>

          <!-- 发送中轻提示（气泡状态为真源，此处仅提示会话内有消息在途） -->
          <div v-if="hasSendingMessage" class="send-status">
            <Spin size="small" />
            <span class="send-result">消息发送中，请稍候…</span>
          </div>

          <!-- 回复输入框（文本 / 图片 / 表情；v1 媒体单次一种） -->
          <div class="chat-input">
            <input
              ref="imageInput"
              type="file"
              class="hidden-file-input"
              accept="image/png,image/jpeg,image/gif,image/bmp"
              @change="handleImageSelected"
            />
            <div class="chat-input-actions">
              <Tooltip title="发送图片（<3MB，JPG/PNG/GIF/BMP）">
                <Button
                  type="text"
                  size="small"
                  :disabled="replyDisabled || sending || imageUploading"
                  :loading="imageUploading"
                  @click="triggerImagePick"
                >
                  <PictureOutlined />
                </Button>
              </Tooltip>
              <Popover
                :open="emojiOpen"
                trigger="click"
                placement="topLeft"
                :overlay-style="{ width: '360px' }"
                @open-change="handleEmojiOpenChange"
              >
                <template #content>
                  <div v-if="emojiLoading" class="emoji-loading"><Spin size="small" /></div>
                  <div v-else-if="emojiCatalog.length" class="emoji-panel">
                    <button
                      v-for="item in emojiCatalog"
                      :key="item.alt"
                      type="button"
                      class="emoji-item"
                      :title="item.name || item.alt"
                      @click="selectEmoji(item)"
                    >
                      <img :src="item.url" :alt="item.alt" />
                    </button>
                  </div>
                  <Empty v-else :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无表情" />
                </template>
                <Button type="text" size="small" title="选择表情" :disabled="replyDisabled || sending">
                  <SmileOutlined />
                </Button>
              </Popover>
            </div>
            <div class="chat-input-main">
              <div v-if="pendingImage || pendingEmoji" class="pending-media">
                <template v-if="pendingImage">
                  <img class="pending-image" :src="pendingImage.url" alt="待发送图片" />
                  <span class="pending-name">{{ pendingImage.name }}</span>
                  <Button type="link" size="small" @click="clearPendingImage">移除</Button>
                </template>
                <template v-else-if="pendingEmoji">
                  <img class="pending-emoji" :src="pendingEmoji.url" :alt="pendingEmoji.alt" />
                  <span class="pending-name">{{ pendingEmoji.alt }}</span>
                  <Button type="link" size="small" @click="clearPendingEmoji">移除</Button>
                </template>
              </div>
              <textarea
                v-model="replyContent"
                :disabled="replyDisabled || sending || !!pendingImage"
                :placeholder="replyPlaceholder"
                rows="2"
                maxlength="5000"
                @keydown="handleReplyKeydown"
              />
            </div>
            <Tooltip :title="replyDisabled ? '账号未绑定主数据，无法创建回复任务' : '发送（Enter）'">
              <Button
                type="primary"
                class="send-btn"
                :disabled="!canSend"
                :loading="sending"
                @click="handleSendReply"
              >
                <SendOutlined /> 发送
              </Button>
            </Tooltip>
          </div>
        </template>
        <Empty
          v-else
          :image="Empty.PRESENTED_IMAGE_SIMPLE"
          description="选择左侧会话查看消息"
          class="chat-empty"
        />
      </div>
    </div>
  </Card>
</template>

<style scoped>
.workbench-card :deep(.ant-card-body) {
  padding: 16px;
}

.status-banner {
  margin-bottom: 12px;
}

.workbench-body {
  display: flex;
  gap: 12px;
  height: calc(100vh - 260px);
  min-height: 480px;
}

/* ===== 左栏 ===== */
.conv-pane {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border, #f0f0f0);
  border-radius: var(--radius-md, 8px);
  overflow: hidden;
}

.conv-filter {
  display: flex;
  gap: 4px;
  padding: 8px;
  border-bottom: 1px solid var(--color-border, #f0f0f0);
  background: var(--color-bg-layout, #fafafa);
}

.conv-list {
  flex: 1;
  overflow-y: auto;
}

.conv-item {
  padding: 10px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--color-border, #f5f5f5);
  transition: background-color 0.15s ease;
}

.conv-item:hover {
  background: var(--color-bg-layout, #fafafa);
}

.conv-item.active {
  background: #f0f1fd;
}

.conv-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.conv-buyer {
  flex: 1;
  font-weight: 600;
  font-size: 13px;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  font-size: 11px;
  color: var(--color-text-muted);
  flex-shrink: 0;
}

.conv-account {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-account-icon {
  flex-shrink: 0;
  color: var(--color-text-muted);
}

.unbound-warn {
  color: #d46b08;
}

.conv-summary {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-out {
  color: var(--color-primary);
}

.conv-product {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-secondary);
  overflow: hidden;
}

.conv-product-img {
  width: 18px;
  height: 18px;
  border-radius: 3px;
  object-fit: cover;
  flex-shrink: 0;
}

.conv-product-img-fallback {
  flex-shrink: 0;
  color: var(--color-text-muted);
}

.conv-product-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-product-price {
  flex-shrink: 0;
  color: var(--color-error, #ff4d4f);
}

.conv-unbound-product {
  color: var(--color-text-muted);
}

.conv-empty {
  padding: 24px 0;
}

/* ===== 中栏 ===== */
.chat-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border, #f0f0f0);
  border-radius: var(--radius-md, 8px);
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border, #f0f0f0);
  background: var(--color-bg-layout, #fafafa);
}

.chat-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
  flex-shrink: 0;
}

.chat-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.chat-product {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-text-secondary);
  min-width: 0;
}

.chat-product-img {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  object-fit: cover;
  flex-shrink: 0;
}

.chat-product-img-fallback {
  flex-shrink: 0;
  color: var(--color-text-muted);
}

.chat-product-title {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-product-price {
  flex-shrink: 0;
  color: var(--color-error, #ff4d4f);
  font-weight: 600;
}

.chat-product-link {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--color-primary, #5b5bd6);
  display: inline-flex;
  align-items: center;
  gap: 2px;
  text-decoration: none;
}

.chat-product-link:hover {
  text-decoration: underline;
}

.chat-unbound-product {
  font-size: 12px;
  color: var(--color-text-muted);
}

.chat-hint {
  font-size: 12px;
  color: #d46b08;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: var(--color-bg-layout, #fafafa);
}

.load-older {
  text-align: center;
  margin-bottom: 12px;
}

.msg-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.msg-in {
  flex-direction: row;
}

.msg-out {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}

.msg-in .msg-avatar {
  background: #eef0fb;
  color: var(--color-primary);
}

.msg-out .msg-avatar {
  background: var(--color-primary);
  color: #fff;
}

.msg-body {
  max-width: 62%;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.msg-out .msg-body {
  align-items: flex-end;
}

.msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.msg-nick {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.msg-time {
  font-size: 11px;
  color: var(--color-text-muted);
}

.msg-replied {
  font-size: 11px;
}

.msg-send-tag {
  font-size: 11px;
}

.msg-send-failure {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  font-size: 12px;
}

.msg-fail-reason {
  color: var(--color-danger, #d4380d);
}

.msg-retry-btn {
  padding: 0;
  height: auto;
  font-size: 12px;
}

.msg-bubble {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-primary);
}

.msg-in .msg-bubble {
  background: #fff;
  border: 1px solid var(--color-border, #f0f0f0);
  border-top-left-radius: 2px;
}

.msg-out .msg-bubble {
  background: var(--color-primary);
  color: #fff;
  border-top-right-radius: 2px;
}

.msg-ct-type {
  color: var(--color-text-muted);
  font-size: 12px;
}

.msg-out .msg-ct-type {
  color: rgba(255, 255, 255, 0.8);
}

/* 媒体使用中性表面，确保原生控件、错误文案和品牌色链接在收发两侧都可读。 */
.msg-body-media {
  max-width: min(72%, 360px);
}

.msg-body-media .msg-meta {
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.msg-bubble.msg-bubble-media {
  box-sizing: border-box;
  max-width: 100%;
  min-width: 0;
  background: var(--color-surface);
  color: var(--color-text-primary);
  border: 1px solid var(--color-border);
  overflow-wrap: anywhere;
}

@media (max-width: 768px) {
  .msg-body-media {
    max-width: calc(100% - 40px);
  }
}

/* ===== 消息流商品卡（13 号 §4.2/§4.5 extJson 快照渲染） ===== */
/* 卡片自带底色，气泡外层去背景/内边距防双层背景 */
.msg-bubble.msg-bubble-card {
  padding: 0;
  background: transparent;
  border-color: transparent;
}

.msg-bubble :deep(.product-card) {
  display: flex;
  gap: 8px;
  width: 240px;
  padding: 8px;
  border-radius: 6px;
  background: #fff;
  border: 1px solid var(--color-border, #f0f0f0);
  color: var(--color-text-primary);
}

.msg-out :deep(.product-card) {
  background: var(--color-primary);
  border-color: transparent;
  color: #fff;
}

.product-card-img-wrap {
  flex-shrink: 0;
}

.product-card-img {
  width: 72px;
  height: 72px;
  border-radius: 6px;
  object-fit: cover;
  display: block;
}

.product-card-img-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  font-size: 22px;
  color: var(--color-text-muted);
  background: var(--color-bg-layout, #fafafa);
  border-radius: 6px;
}

.product-card-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.product-card-title {
  font-size: 13px;
  line-height: 1.4;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.product-card-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 4px;
}

.product-card-price {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-error, #ff4d4f);
}

.msg-out .product-card-price {
  color: #ffd7c9;
}

.product-card-delivery {
  font-size: 11px;
  color: var(--color-text-muted);
}

.msg-out .product-card-delivery {
  color: rgba(255, 255, 255, 0.75);
}

.product-card-link {
  margin-top: auto;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--color-primary);
  text-decoration: none;
}

.msg-out .product-card-link {
  color: #fff;
}

.product-card-link:hover {
  text-decoration: underline;
}

/* ===== 发送状态与输入 ===== */
.send-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border-top: 1px solid var(--color-border, #f0f0f0);
  background: #fff;
}

.send-result {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.chat-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--color-border, #f0f0f0);
  background: #fff;
}

.chat-input textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--color-border, #d9d9d9);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 13px;
  line-height: 1.5;
  font-family: inherit;
  color: var(--color-text-primary);
  outline: none;
}

.chat-input textarea:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(91, 91, 214, 0.1);
}

.chat-input textarea:disabled {
  background: var(--color-bg-layout, #f5f5f5);
  color: var(--color-text-muted);
  cursor: not-allowed;
}

.send-btn {
  flex-shrink: 0;
}

.hidden-file-input {
  display: none;
}

.chat-input-actions {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex-shrink: 0;
}

.chat-input-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chat-input-main textarea {
  flex: 0 0 auto;
  width: 100%;
}

.pending-media {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border: 1px dashed var(--color-border, #d9d9d9);
  border-radius: 6px;
  background: var(--color-bg-layout, #fafafa);
}

.pending-image {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
}

.pending-emoji {
  width: 28px;
  height: 28px;
  object-fit: contain;
  flex-shrink: 0;
}

.pending-name {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.emoji-panel {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
  max-height: 280px;
  overflow-y: auto;
}

.emoji-item {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
}

.emoji-item:hover {
  background: var(--color-bg-layout, #f0f0f0);
}

.emoji-item img {
  width: 24px;
  height: 24px;
  object-fit: contain;
}

.emoji-loading {
  display: flex;
  justify-content: center;
  padding: 24px;
}

.chat-empty {
  margin: auto;
}
</style>
