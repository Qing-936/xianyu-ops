<script setup lang="ts">
defineOptions({ name: 'Welcome' });
</script>

<template>
  <div class="welcome-container">
    <div class="welcome-brand">
      <span class="welcome-brand-mark">晴</span>
    </div>
    <h1 class="welcome-title">晴多多平台 欢迎您！</h1>
    <p class="welcome-subtitle">请从左侧菜单选择功能开始使用</p>
  </div>
</template>

<style scoped>
.welcome-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  background: var(--color-bg);
}

.welcome-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  margin-bottom: var(--spacing-xl);
  border-radius: var(--radius-lg);
  background: var(--color-primary);
}

.welcome-brand-mark {
  color: #fff;
  font-size: 28px;
  font-weight: 600;
  line-height: 1;
}

.welcome-title {
  margin: 0 0 var(--spacing-md);
  color: var(--color-text-primary);
  font-size: 24px;
  font-weight: 600;
}

.welcome-subtitle {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 14px;
}
</style>
