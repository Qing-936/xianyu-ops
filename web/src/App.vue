<script setup lang="ts">
import { ref, computed } from 'vue';
import { theme } from 'ant-design-vue';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

defineOptions({ name: 'App' });

dayjs.locale('zh-cn');

// 简单的暗色模式支持，默认亮色
const isDark = ref(false);

const tokenTheme = computed(() => ({
  algorithm: isDark.value ? [theme.darkAlgorithm] : [theme.defaultAlgorithm],
  token: {
    colorPrimary: '#5b5bd6',
    colorLink: '#5b5bd6',
    colorError: '#e5484d',
    colorSuccess: '#30a46c',
    colorWarning: '#f5a623',
    colorInfo: '#0091ff',
    borderRadius: 4,
  },
}));
</script>

<template>
  <a-config-provider :theme="tokenTheme" :locale="zhCN">
    <router-view />
  </a-config-provider>
</template>
