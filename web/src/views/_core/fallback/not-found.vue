<script setup lang="ts">
defineOptions({ name: 'NotFound' });
import { useRouter } from 'vue-router';
import { computed } from 'vue';
import { Button, Result } from 'ant-design-vue';
import { usePermissionStore } from '#/store/permission';
const router = useRouter();
const permissionStore = usePermissionStore();

/** 返回目标：启用菜单过滤时回到用户第一个可访问页面，避免回到默认首页再次落入 404 */
const backPath = computed(() =>
  permissionStore.menuFilterEnabled && permissionStore.firstLeafMenuPath ? permissionStore.firstLeafMenuPath : '/',
);
</script>

<template>
  <Result status="404" title="404" sub-title="抱歉，您访问的页面不存在">
    <template #extra>
      <Button type="primary" @click="router.push(backPath)">返回首页</Button>
    </template>
  </Result>
</template>
