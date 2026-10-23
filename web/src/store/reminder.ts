import { defineStore } from 'pinia';
import { reminderSources } from '#/reminders';
import { ApiError } from '#/utils/request';
import { playReminderVoice, preheatVoiceNow } from '#/utils/reminder-voice';
import { router } from '#/router';

/**
 * 全局消息提醒中心（14 号 §5.2）：前端聚合架构核心。
 * 各业务源提供轻量 count 接口（见 #/reminders 注册表），本 store 统一
 * 轮询 → 聚合 → 渲染（标题/菜单徽标/顶栏铃铛）→ 触发语音播报。
 */

/** 默认 Tab 标题（index.html 静态值作为首屏兜底保留，此后由 store 统一管理，14 号 §5.3） */
const DEFAULT_TITLE = 'XianyuOps 管理台';

/** 语音偏好 localStorage 键（浏览器级偏好，首次无记录 = 开启，14 号 §5.7） */
const VOICE_ENABLED_KEY = 'xianyuops.reminder.voice-enabled';

/** 显示上限（与 antd Badge overflowCount 一致，14 号 §5.3） */
const OVERFLOW_COUNT = 99;

/** 轮询计时器（模块级，避免被 Pinia 响应式包装，14 号 §5.2） */
let timer: ReturnType<typeof setInterval> | null = null;

/** 单轮在途标记：上轮未返回则跳过本轮，防慢网络下轮询叠加 */
let pollingInflight = false;

/** 计数归一：非数字/负数/小数统一归零取整 */
function clampCount(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0;
}

/** 标题数字格式：≤99 显数字，>99 显 99+ */
function formatCount(count: number): string {
  return count > OVERFLOW_COUNT ? `${OVERFLOW_COUNT}+` : String(count);
}

/** 读取语音开关偏好（localStorage 不可用时默认开启） */
function readVoiceEnabled(): boolean {
  try {
    return localStorage.getItem(VOICE_ENABLED_KEY) !== 'false';
  } catch {
    return true;
  }
}

export const useReminderStore = defineStore('reminder', {
  state: (): {
    /** 各来源当前未读数（key = ReminderSource.key） */
    counts: Record<string, number>;
    /** source 级熔断标记（403 后置 true，跳过后续轮询，14 号 §5.2） */
    disabled: Record<string, boolean>;
    /** 全局轮询是否运行中 */
    polling: boolean;
    /** 语音提醒开关（localStorage 持久化） */
    voiceEnabled: boolean;
  } => ({
    counts: {},
    disabled: {},
    polling: false,
    voiceEnabled: readVoiceEnabled(),
  }),

  getters: {
    /** 全部来源未读总数（顶栏铃铛 / 调试用） */
    totalCount: (state) => Object.values(state.counts).reduce((sum, n) => sum + n, 0),
    /** 指定来源未读数（菜单徽标用；未注册/未加载按 0） */
    countOf: (state) => (key: string) => state.counts[key] ?? 0,
    /** 有未读的来源（标题拼接用） */
    activeSources: (state) =>
      reminderSources
        .filter((s) => (state.counts[s.key] ?? 0) > 0)
        .map((s) => ({ key: s.key, label: s.label, count: state.counts[s.key] ?? 0 })),
  },

  actions: {
    /**
     * 启动全局轮询（14 号 §5.5）：立即 poll 一次 + 固定间隔。
     * 与页面无关——不管运营停在哪个页面，全局提醒持续生效。
     */
    start(intervalMs = 10_000) {
      if (this.polling) return;
      this.polling = true;
      void this.poll();
      timer = setInterval(() => void this.poll(), intervalMs);
    },

    /** 停止轮询（布局卸载/登出时调用） */
    stop() {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
      this.polling = false;
    },

    /**
     * 单轮轮询（14 号 §5.2 失败语义）：
     * - Promise.allSettled 多来源隔离，单 source 失败不拖累其他 source；
     * - 403（无权限）→ 计数归零 + 熔断（后续跳过该 source，防幽灵提醒）；
     * - 其他异常（网络/5xx）→ 静默保持上次值，下一轮重试（请求已走静默通道，不弹窗）；
     * - 首轮拿到值的来源只做基线播种、不触发播报——否则刷新页面会把存量未读
     *   播成「新增」（增量 = 本轮 count − 上轮 count，上轮不存在则无增量）。
     */
    async poll() {
      if (pollingInflight) return;
      pollingInflight = true;
      try {
        const sources = reminderSources.filter((s) => !this.disabled[s.key]);
        const results = await Promise.allSettled(sources.map((s) => s.pollFn()));
        const increases: { key: string; label: string; delta: number }[] = [];
        results.forEach((result, index) => {
          const source = sources[index];
          if (result.status === 'rejected') {
            // 403：权限被回收 → 归零 + 熔断（恢复需刷新页面，14 号 §5.2）
            if (result.reason instanceof ApiError && result.reason.code === 403) {
              this.counts[source.key] = 0;
              this.disabled[source.key] = true;
            }
            // 其余异常静默保持上次值
            return;
          }
          const seeded = source.key in this.counts; // 是否已有前值（基线播种标记）
          const next = clampCount(result.value);
          const prev = this.counts[source.key] ?? 0;
          this.counts[source.key] = next;
          if (seeded && next > prev) {
            increases.push({ key: source.key, label: source.label, delta: next - prev });
          }
        });
        this.applyTitle();
        this.maybePlayVoice(increases);
      } finally {
        pollingInflight = false;
      }
    },

    /** 外部联动写入（工作台页标记已读后即时同步，14 号 §5.6） */
    setCount(key: string, count: number) {
      this.counts[key] = clampCount(count);
      this.applyTitle();
    },

    /**
     * 计数 diff 播报触发（14 号 §5.7）：
     * 增量 > 0 + 开关开启 + 不在「有新消息的那个来源」的页面（人正在看，不打扰——
     * 当前路由 meta.reminderKey 与来源 key 相同时静默，等价于「停留工作台页静音」）。
     * 单次调用只播报一次（调用方保证每轮最多调一次，批量 +50 也只播一次总数）。
     */
    maybePlayVoice(increases: { key: string; label: string; delta: number }[]) {
      if (!this.voiceEnabled || increases.length === 0) return;
      const currentReminderKey = router.currentRoute.value.meta?.reminderKey as string | undefined;
      const audible = increases.filter((i) => i.key !== currentReminderKey);
      if (audible.length > 0) {
        playReminderVoice(audible.map(({ label, delta }) => ({ label, delta })));
      }
    },

    /** 语音开关（顶栏铃铛，14 号 §5.7）：持久化 + 开启时即时预热（点击本身即用户交互） */
    setVoiceEnabled(enabled: boolean) {
      this.voiceEnabled = enabled;
      try {
        localStorage.setItem(VOICE_ENABLED_KEY, enabled ? 'true' : 'false');
      } catch {
        // 隐私模式等 localStorage 不可用时静默降级（仅本次会话生效）
      }
      if (enabled) {
        preheatVoiceNow();
      }
    },

    /** 标题联动（14 号 §5.3）：无未读恢复默认；有未读「【闲鱼 3 条新消息】XianyuOps 管理台」 */
    applyTitle() {
      const parts = reminderSources
        .filter((s) => (this.counts[s.key] ?? 0) > 0)
        .map((s) => `${s.label} ${formatCount(this.counts[s.key] ?? 0)} 条新消息`);
      document.title = parts.length > 0 ? `【${parts.join(' · ')}】${DEFAULT_TITLE}` : DEFAULT_TITLE;
    },

    /** 登出清空（14 号 §5.5）：停轮询 + 清计数/熔断 + 恢复标题（语音偏好保留） */
    reset() {
      this.stop();
      this.counts = {};
      this.disabled = {};
      this.applyTitle();
    },
  },
});
