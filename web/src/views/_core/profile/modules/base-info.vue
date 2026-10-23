<script setup lang="ts">
import type { SystemUserProfileApi } from '#/api/system/user/profile';
import type { Rule } from 'ant-design-vue/es/form';

import { ref, watch } from 'vue';
import { message, Form, FormItem, Input, Select, SelectOption, Button } from 'ant-design-vue';
import { updateUserProfile } from '#/api/system/user/profile';

const props = defineProps<{
  profile?: SystemUserProfileApi.UserProfileRespVO;
}>();

const emit = defineEmits<{
  (e: 'success'): void;
}>();

const formState = ref({
  nickname: '',
  mobile: '',
  email: '',
  sex: undefined as number | undefined,
});

const loading = ref(false);

/** 性别选项 */
const sexOptions = [
  { label: '男', value: 1 },
  { label: '女', value: 2 },
  { label: '未知', value: 0 },
];

/** 表单验证规则 */
const rules: Record<string, Rule[]> = {
  nickname: [{ required: true, message: '请输入用户昵称', trigger: 'blur' }],
  email: [{ type: 'email', message: '请输入正确的邮箱地址', trigger: 'blur' }],
  mobile: [{ pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号', trigger: 'blur' }],
};

/** 提交表单 */
async function handleSubmit() {
  loading.value = true;
  try {
    await updateUserProfile({
      nickname: formState.value.nickname,
      mobile: formState.value.mobile,
      email: formState.value.email,
      sex: formState.value.sex,
    });
    message.success('更新成功');
    emit('success');
  } catch (error) {
    console.error(error);
  } finally {
    loading.value = false;
  }
}

/** 监听 profile 变化，填充表单 */
watch(
  () => props.profile,
  (newProfile) => {
    if (newProfile) {
      formState.value = {
        nickname: newProfile.nickname || '',
        mobile: newProfile.mobile || '',
        email: newProfile.email || '',
        sex: newProfile.sex,
      };
    }
  },
  { immediate: true },
);
</script>

<template>
  <Form
    :model="formState"
    :rules="rules"
    :label-col="{ span: 6 }"
    :wrapper-col="{ span: 18 }"
    style="max-width: 500px"
  >
    <FormItem label="用户昵称" name="nickname">
      <Input v-model:value="formState.nickname" placeholder="请输入用户昵称" />
    </FormItem>
    <FormItem label="手机号码" name="mobile">
      <Input v-model:value="formState.mobile" placeholder="请输入手机号码" />
    </FormItem>
    <FormItem label="用户邮箱" name="email">
      <Input v-model:value="formState.email" placeholder="请输入用户邮箱" />
    </FormItem>
    <FormItem label="用户性别" name="sex">
      <Select v-model:value="formState.sex" placeholder="请选择性别">
        <SelectOption v-for="opt in sexOptions" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </SelectOption>
      </Select>
    </FormItem>
    <FormItem :wrapper-col="{ offset: 6, span: 18 }">
      <Button type="primary" :loading="loading" @click="handleSubmit">
        更新信息
      </Button>
    </FormItem>
  </Form>
</template>
