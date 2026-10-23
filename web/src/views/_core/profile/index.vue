<script setup lang="ts">
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { onMounted, ref } from 'vue';
import { Card, Tabs } from 'ant-design-vue';

import { useAuthStore } from '#/store/auth';
import { getUserProfile } from '#/api/system/user/profile';

import ProfileUser from './modules/profile-user.vue';
import BaseInfo from './modules/base-info.vue';
import ResetPwd from './modules/reset-pwd.vue';

defineOptions({ name: 'Profile' });

const authStore = useAuthStore();
const activeKey = ref('basicInfo');

/** 加载个人信息 */
const profile = ref<SystemUserProfileApi.UserProfileRespVO>();
async function loadProfile() {
  profile.value = await getUserProfile();
}

/** 刷新个人信息 */
async function refreshProfile() {
  // 重新加载个人信息
  await loadProfile();
  // 更新全局 store 中的用户信息
  await authStore.fetchUserInfoAndPermissions();
}

/** 初始化 */
onMounted(loadProfile);
</script>

<template>
  <div class="profile-container">
    <div class="profile-layout">
      <!-- 左侧 个人信息卡片 -->
      <Card title="个人信息" class="profile-card">
        <ProfileUser :profile="profile" @success="refreshProfile" />
      </Card>

      <!-- 右侧 设置卡片 -->
      <Card style="flex: 1">
        <Tabs v-model:activeKey="activeKey" style="margin-top: -16px">
          <Tabs.TabPane key="basicInfo" tab="基本设置">
            <div style="padding: 24px 0">
              <BaseInfo :profile="profile" @success="refreshProfile" />
            </div>
          </Tabs.TabPane>
          <Tabs.TabPane key="resetPwd" tab="密码设置">
            <div style="padding: 24px 0">
              <ResetPwd />
            </div>
          </Tabs.TabPane>
        </Tabs>
      </Card>
    </div>
  </div>
</template>

<style scoped>
.profile-container {
  padding: 24px;
}

.profile-layout {
  display: flex;
  gap: 16px;
}

.profile-card {
  width: 40%;
  min-width: 360px;
  flex-shrink: 0;
}

/* 响应式: 移动端 (<768px) */
@media (max-width: 767px) {
  .profile-container {
    padding: 12px;
  }

  .profile-layout {
    flex-direction: column;
  }

  .profile-card {
    width: 100%;
    min-width: 0;
  }
}
</style>
