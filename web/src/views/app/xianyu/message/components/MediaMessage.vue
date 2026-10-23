<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Image as AntImage } from 'ant-design-vue';
import { ExclamationCircleOutlined, LinkOutlined } from '@ant-design/icons-vue';
import { isXianyuMediaType } from '#/api/app/xianyu/message';
import type { XianyuMessageApi } from '#/api/app/xianyu/message';

defineOptions({ name: 'XianyuMediaMessage' });

const props = defineProps<{
  extJson?: string;
  caption?: string;
}>();

const MEDIA_LABEL: Record<XianyuMessageApi.MediaType, string> = {
  image: '图片', emoji: '表情', audio: '语音', video: '视频',
};
// 与后端 MEDIA_HOSTS 保持一致：阿里系 CDN + 闲鱼/本项目域名 + 钉钉 IM 媒体 CDN
const TRUSTED_DOMAINS = ['alicdn.com', 'taobaocdn.com', 'tbcdn.cn', 'aliyuncs.com', 'goofish.com',
  'dingtalk.com', 'dingtalk.cn', 'aliimg.com'];
const MAX_MEDIA_ITEMS = 20;
const failedItems = ref<Record<number, boolean>>({});

type MediaParseResult = { ext: XianyuMessageApi.MediaExt; error?: never }
  | { ext?: never; error: string };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isOptionalString(value: unknown): value is string | undefined {
  return value === undefined || typeof value === 'string';
}

function isMediaItem(value: unknown): value is XianyuMessageApi.MediaItem {
  return isRecord(value) && isXianyuMediaType(value.type)
    && isOptionalString(value.url) && isOptionalString(value.posterUrl)
    && isOptionalString(value.alt) && isOptionalString(value.unavailableReason)
    && (value.duration === undefined
      || (typeof value.duration === 'number' && Number.isFinite(value.duration) && value.duration >= 0 && value.duration <= 86400));
}

function parseMediaExt(raw: unknown): MediaParseResult {
  if (typeof raw !== 'string' || !raw.trim()) return { error: '媒体消息不可用：缺少资源信息' };
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { error: '媒体消息不可用：资源信息不是有效的 JSON' };
  }
  if (!isRecord(value) || value.type !== 'media' || !Array.isArray(value.items)
    || !isOptionalString(value.sourceMessageId) || !isOptionalString(value.dedupKey)) {
    return { error: '媒体消息不可用：资源信息结构不合法' };
  }
  const items: unknown[] = value.items;
  if (!items.length) return { error: '媒体消息不可用：资源列表为空' };
  if (items.length > MAX_MEDIA_ITEMS) return { error: '媒体消息不可用：资源数量超过 20 个上限' };
  if (!items.every(isMediaItem)) return { error: '媒体消息不可用：资源类型或字段格式不合法' };
  return { ext: { type: 'media', items, sourceMessageId: value.sourceMessageId, dedupKey: value.dedupKey } };
}

/** 先检查原始 authority，防止 URL 自动修复反斜杠、编码域名或空 userinfo；再校验规范化结果。 */
function normalizeMediaUrl(raw?: string): string | undefined {
  if (!raw || raw.length > 4096 || /[\\\u0000-\u001f\u007f-\u009f]/u.test(raw)) return undefined;
  const text = raw.trim();
  if (/\s/u.test(text)) return undefined;
  const absolute = text.startsWith('//') ? `https:${text}` : text;
  const authority = /^https?:\/\/([^/?#]+)/i.exec(absolute)?.[1];
  if (!authority || !/^[a-z0-9.-]+(?::\d+)?$/i.test(authority)) return undefined;
  try {
    if (/[\u0000-\u001f\u007f-\u009f]/u.test(decodeURIComponent(absolute))) return undefined;
    const url = new URL(absolute);
    if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password || url.port) {
      return undefined;
    }
    const host = url.hostname;
    if (host.length > 253 || !host.split('.').every((part) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(part))) {
      return undefined;
    }
    // 精确域名 / 点分子域匹配同时排除 IP、伪造后缀和非可信站点。
    if (!TRUSTED_DOMAINS.some((domain) => host === domain || host.endsWith(`.${domain}`))) return undefined;
    url.protocol = 'https:';
    return url.href;
  } catch {
    return undefined;
  }
}

const parsed = computed(() => parseMediaExt(props.extJson));
const items = computed(() => (parsed.value.ext?.items ?? []).map((item) => {
  const url = normalizeMediaUrl(item.url);
  const posterUrl = normalizeMediaUrl(item.posterUrl);
  const label = MEDIA_LABEL[item.type];
  return {
    type: item.type,
    url,
    posterUrl,
    label,
    alt: item.alt?.trim() || `${label}消息`,
    textEmoji: item.type === 'emoji' ? item.alt?.trim() : '',
    duration: item.duration,
    unavailable: url || (item.type === 'emoji' && item.alt?.trim() && !item.url && !item.unavailableReason) ? ''
      : `${label}不可用：${item.url?.trim()
        ? '资源地址不合法或不在可信域名范围内'
        : item.unavailableReason?.trim() || '未提供资源地址'}`,
    posterUnavailable: item.type === 'video' && item.posterUrl?.trim() && !posterUrl
      ? '视频封面不可用：封面地址不合法或不在可信域名范围内' : '',
  };
}));

// 仅资源快照变化时重置错误；定时刷新相同消息不重建播放器、不丢失错误提示。
watch(() => props.extJson, () => { failedItems.value = {}; }, { flush: 'sync' });
// 不监听 load / loadedmetadata 触发滚动，避免加载旧消息或切换会话后被迟到的媒体事件拉回底部。
</script>

<template>
  <div class="media-message">
    <div class="media-kind"><slot name="label" /></div>
    <div v-if="parsed.error" class="media-notice" role="status">
      <ExclamationCircleOutlined class="media-error-icon" /> {{ parsed.error }}
    </div>
    <div v-for="(item, index) in items" :key="index" class="media-item" :class="{ 'media-emoji': item.type === 'emoji' }">
      <template v-if="item.url && !failedItems[index]">
        <AntImage
          v-if="item.type === 'image' || item.type === 'emoji'"
          :key="item.url"
          :src="item.url"
          :alt="item.alt"
          :preview="true"
          class="media-image"
          @error="failedItems[index] = true"
        />
        <audio
          v-else-if="item.type === 'audio'"
          :key="item.url"
          :src="item.url"
          :aria-label="`语音消息：${item.alt}`"
          class="media-audio"
          controls
          preload="none"
          @error="failedItems[index] = true"
        >浏览器不支持语音播放，请使用下方链接打开原资源。</audio>
        <video
          v-else-if="item.type === 'video'"
          :key="item.url"
          :src="item.url"
          :poster="item.posterUrl"
          :aria-label="`视频消息：${item.alt}`"
          class="media-video"
          controls
          playsinline
          preload="none"
          @error="failedItems[index] = true"
        >浏览器不支持视频播放，请使用下方链接打开原资源。</video>
      </template>
      <span v-if="item.textEmoji && (!item.url || failedItems[index])">{{ item.textEmoji }}</span>
      <span v-if="item.type === 'audio' && item.url" class="media-notice">AMR 语音可能无法直接播放，可打开原资源。</span>
      <span v-if="item.unavailable || failedItems[index]" class="media-notice" role="status">
        <ExclamationCircleOutlined class="media-error-icon" />
        {{ item.unavailable || `${item.label}加载或播放失败，资源可能已失效或浏览器不支持此格式，请尝试打开原资源。` }}
      </span>
      <span v-if="item.posterUnavailable" class="media-notice" role="status">{{ item.posterUnavailable }}</span>
      <span v-if="item.duration !== undefined && (item.type === 'audio' || item.type === 'video')" class="media-duration">
        时长 {{ item.duration }} 秒
      </span>
      <a
        v-if="item.url && (failedItems[index] || item.type === 'audio' || item.type === 'video')"
        :href="item.url"
        target="_blank"
        rel="noopener noreferrer"
        class="media-link"
        :aria-label="`在新标签页打开原${item.label}`"
      ><LinkOutlined /> 打开原{{ item.label }}（新标签页）</a>
    </div>
    <span v-if="caption" class="media-caption">{{ caption }}</span>
  </div>
</template>

<style scoped>
.media-message {
  max-width: 320px;
  min-width: 0;
  white-space: normal;
  overflow-wrap: anywhere;
}

.media-kind {
  margin-bottom: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: 12px;
}

.media-item {
  display: block;
  max-width: 100%;
  margin-bottom: var(--spacing-xs);
}

.media-item:not(.media-emoji) {
  width: 280px;
}

.media-item :deep(.ant-image) {
  display: inline-block;
  max-width: 100%;
  vertical-align: middle;
}

.media-item :deep(.ant-image-img) {
  display: block;
  width: auto;
  max-width: 100%;
  max-height: 240px;
  object-fit: contain;
  border-radius: var(--radius-sm);
}

.media-emoji {
  display: inline-block;
  vertical-align: middle;
  margin-right: var(--spacing-xs);
}

.media-emoji :deep(.ant-image-img) {
  width: 48px;
  height: 48px;
}

.media-audio,
.media-video {
  display: block;
  width: 100%;
  max-width: 320px;
  border-radius: var(--radius-sm);
}

.media-video {
  aspect-ratio: 16 / 9;
  max-height: 240px;
  object-fit: contain;
  background: var(--color-surface-alt);
}

.media-notice {
  display: block;
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: pre-wrap;
}

.media-error-icon {
  color: var(--color-error);
}

.media-duration {
  display: block;
  color: var(--color-text-secondary);
  font-size: 12px;
  margin-top: var(--spacing-xs);
}

.media-link {
  display: inline-block;
  color: var(--color-primary);
  font-size: 12px;
  text-decoration: underline;
}

.media-link:hover {
  color: var(--color-primary-hover);
}

.media-link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.media-caption {
  white-space: pre-wrap;
}

@media (max-width: 768px) {
  .media-message {
    max-width: 100%;
  }
}
</style>
