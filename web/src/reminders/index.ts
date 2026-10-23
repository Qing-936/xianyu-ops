import { getXianyuUnreadCount } from '#/api/app/xianyu/message';

/**
 * 提醒来源注册表（14 号 §4/§6：前端聚合架构的唯一定义点）。
 *
 * 新增提醒来源三步法（14 号 §6）：
 * 1. 后端提供轻量未读 count 接口（COUNT 查询 + @ApiAccessLog(enable = false)）；
 * 2. 前端 API 层加函数（必须走静默请求 __silent: true，否则轮询错误会弹全局提示）；
 * 3. 在本数组注册一条 source（key 与菜单路由 meta.reminderKey 对应）。
 */
export interface ReminderSource {
  /** 唯一键（与菜单路由 meta.reminderKey 对应） */
  key: string;
  /** 来源名（标题文案/语音播报用，如「闲鱼」） */
  label: string;
  /** 拉取该来源未读数（必须走静默请求，14 号 §5.2） */
  pollFn: () => Promise<number>;
}

export const reminderSources: ReminderSource[] = [
  {
    key: 'xianyu-message',
    label: '闲鱼',
    pollFn: getXianyuUnreadCount,
  },
];

/** 按 key 查来源（菜单徽标渲染等场景用） */
export function findReminderSource(key: string): ReminderSource | undefined {
  return reminderSources.find((s) => s.key === key);
}
