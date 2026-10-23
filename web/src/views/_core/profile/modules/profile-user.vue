<script setup lang="ts">
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { ref } from 'vue';
import { message, Upload } from 'ant-design-vue';
import { UserOutlined, CameraOutlined } from '@ant-design/icons-vue';
import { Descriptions, Avatar } from 'ant-design-vue';
import { updateUserProfile } from '#/api/system/user/profile';
import { uploadFile } from '#/api/infra/file';
import { formatDateTime } from '#/utils/format';

const props = defineProps<{
  profile?: SystemUserProfileApi.UserProfileRespVO;
}>();

const emit = defineEmits<{
  (e: 'success'): void;
}>();

const uploading = ref(false);

/** 上传头像 */
async function handleUpload(options: any) {
  const { file } = options;
  uploading.value = true;
  try {
    // 1. 上传文件
    const avatarUrl = await uploadFile({ file, directory: 'avatar' });
    // 2. 更新用户头像
    await updateUserProfile({ avatar: avatarUrl });
    message.success('头像更新成功');
    emit('success');
  } catch (error) {
    console.error(error);
    message.error('头像上传失败');
  } finally {
    uploading.value = false;
  }
}
</script>

<template>
  <div v-if="profile">
    <!-- 头像区域 -->
    <div style="display: flex; flex-direction: column; align-items: center; margin-bottom: 24px">
      <Upload
        :show-upload-list="false"
        :custom-request="handleUpload"
        accept="image/*"
      >
        <div style="position: relative; cursor: pointer">
          <Avatar :size="120" :src="profile.avatar" style="background-color: #5b5bd6">
            <template #icon><UserOutlined /></template>
          </Avatar>
          <div
            style="
              position: absolute;
              bottom: 0;
              left: 0;
              right: 0;
              height: 32px;
              background: rgba(0, 0, 0, 0.5);
              border-radius: 0 0 60px 60px;
              display: flex;
              align-items: center;
              justify-content: center;
              color: #fff;
            "
          >
            <CameraOutlined />
          </div>
        </div>
      </Upload>
      <div style="margin-top: 12px; font-size: 16px; font-weight: 500">
        {{ profile.nickname }}
      </div>
      <div style="color: var(--color-text-muted); margin-top: 4px">
        {{ profile.roles?.map((role) => role.name).join(', ') || '普通用户' }}
      </div>
    </div>

    <!-- 信息展示 -->
    <Descriptions :column="1" bordered size="small">
      <Descriptions.Item label="用户账号">
        <UserOutlined style="margin-right: 8px; color: var(--color-primary)" />
        {{ profile.username }}
      </Descriptions.Item>
      <Descriptions.Item label="手机号码">
        {{ profile.mobile || '-' }}
      </Descriptions.Item>
      <Descriptions.Item label="用户邮箱">
        {{ profile.email || '-' }}
      </Descriptions.Item>
      <Descriptions.Item label="所属部门">
        {{ profile.dept?.name || '-' }}
      </Descriptions.Item>
      <Descriptions.Item label="所属岗位">
        {{ profile.posts && profile.posts.length > 0 ? profile.posts.map((post) => post.name).join(', ') : '-' }}
      </Descriptions.Item>
      <Descriptions.Item label="创建时间">
        {{ formatDateTime(profile.createTime) }}
      </Descriptions.Item>
      <Descriptions.Item label="登录时间">
        {{ formatDateTime(profile.loginDate) }}
      </Descriptions.Item>
    </Descriptions>
  </div>
</template>
