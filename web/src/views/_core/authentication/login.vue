<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Card, Form, Input, Button, message, Select } from 'ant-design-vue';
import type { SelectValue } from 'ant-design-vue/es/select';
import { UserOutlined, LockOutlined, BankOutlined } from '@ant-design/icons-vue';
import { useAuthStore } from '#/store/auth';
import { useTenantStore } from '#/store/tenant';
import { isTenantEnable } from '#/utils/tenant';
import { getSimpleTenantList, getTenantByWebsite } from '#/api/system/tenant';
import type { SystemTenantApi } from '#/api/system/tenant';
import { DEFAULT_HOME_PATH } from '#/router/routes/core';

defineOptions({ name: 'Login' });

const router = useRouter();
const authStore = useAuthStore();
const tenantStore = useTenantStore();
const tenantEnable = isTenantEnable();

const formState = ref({
  username: import.meta.env.VITE_APP_DEFAULT_USERNAME || '',
  password: import.meta.env.VITE_APP_DEFAULT_PASSWORD || '',
  tenantId: undefined as number | undefined,
});

// ============ 租户选择 ============
const tenantList = ref<SystemTenantApi.Tenant[]>([]);
const tenantLoading = ref(false);
/** 租户列表加载失败标记：列表为空且无重试入口会导致无法登录，故显式提示并允许重试 */
const tenantLoadError = ref(false);

/**
 * 初始化租户下拉，默认值优先级（对齐芋道）：
 * 域名匹配(get-by-website) > 上次记住的租户（须仍在列表中，可能已被禁用/删除）> 列表第一个
 */
async function initTenant() {
  if (!tenantEnable) return;
  tenantLoading.value = true;
  tenantLoadError.value = false;
  try {
    const [list, websiteTenant] = await Promise.all([
      getSimpleTenantList().catch(() => {
        tenantLoadError.value = true;
        return [] as SystemTenantApi.Tenant[];
      }),
      getTenantByWebsite(window.location.hostname).catch(() => null),
    ]);
    tenantList.value = list || [];
    const remembered = tenantStore.tenantId;
    const rememberedValid =
      remembered !== null && tenantList.value.some((t) => t.id === remembered);
    const tenant =
      (websiteTenant?.id != null ? websiteTenant : undefined) ??
      (rememberedValid ? tenantList.value.find((t) => t.id === remembered) : undefined) ??
      tenantList.value[0];
    if (tenant?.id != null) {
      formState.value.tenantId = tenant.id;
      tenantStore.setTenantId(tenant.id);
    }
  } finally {
    tenantLoading.value = false;
  }
}

function handleTenantChange(value: SelectValue) {
  tenantStore.setTenantId(value === undefined || value === null ? null : Number(value));
}

onMounted(initTenant);

const loading = ref(false);

async function handleLogin() {
  loading.value = true;
  try {
    await authStore.login({
      username: formState.value.username,
      password: formState.value.password,
    });
    message.success('登录成功');
    const redirect = (router.currentRoute.value.query.redirect as string) || DEFAULT_HOME_PATH;
    await router.push(decodeURIComponent(redirect));
  } catch (error: any) {
    // 错误已在拦截器中处理
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-container">
    <Card class="login-card" :bordered="false">
      <div class="login-header">
        <h2>晴多多 平台</h2>
<!--        <p> 管理系统</p>-->
      </div>

      <Form :model="formState" @finish="handleLogin" layout="vertical">
        <Form.Item v-if="tenantEnable" name="tenantId" :rules="[{ required: true, message: '请选择租户' }]">
          <Select
            v-model:value="formState.tenantId"
            placeholder="请选择租户"
            size="large"
            :loading="tenantLoading"
            :options="tenantList.map((t) => ({ label: t.name, value: t.id as number }))"
            @change="handleTenantChange"
          >
            <template #suffixIcon><BankOutlined /></template>
          </Select>
          <div v-if="tenantLoadError" class="tenant-error">
            租户列表加载失败，<a @click="initTenant">点击重试</a>
          </div>
        </Form.Item>

        <Form.Item name="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <Input
            v-model:value="formState.username"
            placeholder="请输入用户名"
            size="large"
          >
            <template #prefix><UserOutlined /></template>
          </Input>
        </Form.Item>

        <Form.Item name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <Input.Password
            v-model:value="formState.password"
            placeholder="请输入密码"
            size="large"
          >
            <template #prefix><LockOutlined /></template>
          </Input.Password>
        </Form.Item>

        <Form.Item>
          <Button
            type="primary"
            html-type="submit"
            :loading="loading"
            size="large"
            block
          >
            登 录
          </Button>
        </Form.Item>
      </Form>
    </Card>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 100vh;
  background: var(--color-bg);
}

.login-card {
  width: 400px;
  max-width: calc(100vw - 32px);
  border-radius: var(--radius-lg);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.login-header {
  text-align: center;
  margin-bottom: 24px;
}

.login-header h2 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: bold;
  color: var(--color-text-primary);
}

.login-header p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 14px;
}

.tenant-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-error, #ff4d4f);
}

</style>
