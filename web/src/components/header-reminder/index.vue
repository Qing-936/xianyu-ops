<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { Badge, Button, Dropdown, Switch } from 'ant-design-vue';
import { BellOutlined, RightOutlined } from '@ant-design/icons-vue';
import { useReminderStore } from '#/store/reminder';

/**
 * 顶栏提醒铃铛（14 号 §5.7）：
 * 未读 Badge（与 Tab 标题同源）+ 跳转消息工作台 + 语音开关。
 * 不做来源明细列表（与 Tab 标题多来源格式重复，已修剪）。
 */
defineOptions({ name: 'HeaderReminder' });

const router = useRouter();
const reminderStore = useReminderStore();

/** 全局未读总数 */
const badgeCount = computed(() => reminderStore.totalCount);

/** 语音开关（双向绑定 store，localStorage 持久化） */
const voiceEnabled = computed({
  get: () => reminderStore.voiceEnabled,
  set: (value: boolean) => reminderStore.setVoiceEnabled(value),
});

/** 跳转消息工作台 */
function goWorkbench() {
  router.push('/app/xianyu/service/message');
}
</script>

<template>
  <Dropdown placement="bottomRight" :trigger="['click']">
    <Button type="text" class="reminder-bell-btn">
      <Badge :count="badgeCount" :overflow-count="99" size="small">
        <BellOutlined class="reminder-bell-icon" />
      </Badge>
    </Button>
    <template #overlay>
      <div class="reminder-panel">
        <div class="reminder-panel-row reminder-panel-link" @click="goWorkbench">
          <span>前往消息工作台</span>
          <RightOutlined class="reminder-panel-arrow" />
        </div>
        <div class="reminder-panel-row">
          <span>语音提醒</span>
          <Switch v-model:checked="voiceEnabled" size="small" />
        </div>
      </div>
    </template>
  </Dropdown>
</template>

<style scoped>
.reminder-bell-btn {
  display: inline-flex;
  align-items: center;
  font-size: 16px;
  color: var(--color-text-secondary);
}

.reminder-bell-icon {
  font-size: 16px;
}

/* 面板：扁平克制（静态无阴影），用描边分层 */
.reminder-panel {
  min-width: 200px;
  padding: var(--spacing-xs) 0;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
}

.reminder-panel-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-lg);
  padding: var(--spacing-sm) var(--spacing-md);
  font-size: 14px;
  color: var(--color-text-primary);
}

.reminder-panel-link {
  cursor: pointer;
}

.reminder-panel-link:hover {
  background: var(--color-surface-alt);
}

.reminder-panel-arrow {
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
