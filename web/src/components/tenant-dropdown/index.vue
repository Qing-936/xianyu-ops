<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { Dropdown, Menu, MenuItem, MenuDivider, Button, Space, message } from 'ant-design-vue';
import { SwapOutlined, CheckOutlined } from '@ant-design/icons-vue';
import { getSimpleTenantList } from '#/api/system/tenant';
import type { SystemTenantApi } from '#/api/system/tenant';
import { useTenantStore } from '#/store/tenant';
import { usePermissionStore } from '#/store/permission';
import { isTenantEnable } from '#/utils/tenant';

defineOptions({ name: 'TenantDropdown' });

const tenantStore = useTenantStore();
const permissionStore = usePermissionStore();

/** 显示条件：多租户开启 + 持有租户切换权限（对齐芋道 system:tenant:visit） */
const visible = computed(() => isTenantEnable() && permissionStore.hasPermission('system:tenant:visit'));

const tenants = ref<SystemTenantApi.Tenant[]>([]);

/** 当前访问租户名（未切换时为平台租户视角） */
const currentName = computed(() => {
  if (tenantStore.visitTenantId === null) return '平台租户';
  return tenants.value.find((t) => t.id === tenantStore.visitTenantId)?.name ?? '平台租户';
});

onMounted(async () => {
  if (!visible.value) return;
  try {
    tenants.value = (await getSimpleTenantList()) || [];
  } catch {
    // 租户列表加载失败不阻塞布局
  }
});

/** 切换访问租户：写入 visitTenantId 后整页刷新，使所有数据落到目标租户 */
function handleTenantChange(tenant: SystemTenantApi.Tenant) {
  if (tenant.id === tenantStore.visitTenantId) return;
  tenantStore.setVisitTenantId(tenant.id ?? null);
  message.success(`切换当前租户为: ${tenant.name}`);
  window.location.reload();
}

/** 回到平台租户视角（清除 visit-tenant-id 头） */
function handleBackToPlatform() {
  tenantStore.setVisitTenantId(null);
  message.success('已回到平台租户');
  window.location.reload();
}
</script>

<template>
  <Dropdown v-if="visible">
    <Button type="text" class="tenant-btn">
      <Space>
        <SwapOutlined />
        <span class="tenant-name">{{ currentName }}</span>
      </Space>
    </Button>
    <template #overlay>
      <Menu>
        <MenuItem
          v-for="tenant in tenants"
          :key="tenant.id"
          :disabled="tenant.id === tenantStore.visitTenantId"
          @click="handleTenantChange(tenant)"
        >
          <Space>
            <CheckOutlined v-if="tenant.id === tenantStore.visitTenantId" />
            <span v-else style="display: inline-block; width: 14px"></span>
            {{ tenant.name }}
          </Space>
        </MenuItem>
        <template v-if="tenantStore.visitTenantId !== null">
          <MenuDivider />
          <MenuItem key="__platform__" @click="handleBackToPlatform">
            <Space>
              <span style="display: inline-block; width: 14px"></span>
              回到平台租户
            </Space>
          </MenuItem>
        </template>
      </Menu>
    </template>
  </Dropdown>
</template>

<style scoped>
.tenant-btn {
  font-size: 14px;
}

.tenant-name {
  max-width: 128px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
