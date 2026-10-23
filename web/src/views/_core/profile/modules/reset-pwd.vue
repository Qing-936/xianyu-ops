<script setup lang="ts">
import type { Rule } from 'ant-design-vue/es/form';

import { ref } from 'vue';
import { message, Form, FormItem, Input, Button } from 'ant-design-vue';
import { updateUserPassword } from '#/api/system/user/profile';

const formState = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
});

const loading = ref(false);

/** 表单验证规则 */
const rules: Record<string, Rule[]> = {
  oldPassword: [
    { required: true, message: '请输入旧密码', trigger: 'blur' },
    { min: 5, max: 20, message: '密码长度为 5-20 个字符', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 5, max: 20, message: '密码长度为 5-20 个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请输入确认密码', trigger: 'blur' },
    {
      validator: (_rule: Rule, value: string) => {
        if (value && value !== formState.value.newPassword) {
          return Promise.reject('两次输入的密码不一致');
        }
        return Promise.resolve();
      },
      trigger: 'blur',
    },
  ],
};

/** 提交表单 */
async function handleSubmit() {
  if (formState.value.newPassword !== formState.value.confirmPassword) {
    message.error('新密码和确认密码不一致');
    return;
  }
  if (formState.value.oldPassword === formState.value.newPassword) {
    message.error('新密码不能与旧密码相同');
    return;
  }

  loading.value = true;
  try {
    await updateUserPassword({
      oldPassword: formState.value.oldPassword,
      newPassword: formState.value.newPassword,
    });
    message.success('密码修改成功');
    // 清空表单
    formState.value = {
      oldPassword: '',
      newPassword: '',
      confirmPassword: '',
    };
  } catch (error) {
    console.error(error);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <Form
    :model="formState"
    :rules="rules"
    :label-col="{ span: 6 }"
    :wrapper-col="{ span: 18 }"
    style="max-width: 500px"
  >
    <FormItem label="旧密码" name="oldPassword">
      <Input.Password v-model:value="formState.oldPassword" placeholder="请输入旧密码" />
    </FormItem>
    <FormItem label="新密码" name="newPassword">
      <Input.Password v-model:value="formState.newPassword" placeholder="请输入新密码" />
    </FormItem>
    <FormItem label="确认密码" name="confirmPassword">
      <Input.Password v-model:value="formState.confirmPassword" placeholder="请再次输入新密码" />
    </FormItem>
    <FormItem :wrapper-col="{ offset: 6, span: 18 }">
      <Button type="primary" :loading="loading" @click="handleSubmit">
        修改密码
      </Button>
    </FormItem>
  </Form>
</template>
