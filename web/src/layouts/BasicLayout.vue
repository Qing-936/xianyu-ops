<script setup lang="ts">
import { ref, computed, h, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import {
  Layout,
  LayoutSider,
  LayoutHeader,
  LayoutContent,
  Menu,
  MenuItem,
  SubMenu,
  Avatar,
  Dropdown,
  Button,
  Space,
  Badge,
} from 'ant-design-vue';
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons-vue';
import { useAuthStore } from '#/store/auth';
import { useUserStore } from '#/store/user';
import { usePermissionStore } from '#/store/permission';
import { useReminderStore } from '#/store/reminder';
import { setupVoicePreheat } from '#/utils/reminder-voice';
import { businessRoutes } from '#/router';
import { DEFAULT_HOME_PATH } from '#/router/routes/core';
import { getIconComponent } from '#/utils/icon-map';
import { filterRoutesByMenuPaths } from './menu-route-filter';
import HeaderReminder from '#/components/header-reminder/index.vue';
import TenantDropdown from '#/components/tenant-dropdown/index.vue';
import type { Component } from 'vue';
import type { RouteRecordRaw } from 'vue-router';

defineOptions({ name: 'BasicLayout' });

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const userStore = useUserStore();
const permissionStore = usePermissionStore();
const reminderStore = useReminderStore();

const collapsed = ref(false);
const isMobile = ref(false);

/** 响应式：检测移动端视口 */
function handleResize() {
  const width = window.innerWidth;
  const wasMobile = isMobile.value;
  isMobile.value = width < 768;
  // 进入移动端时自动折叠侧边栏
  if (isMobile.value && !wasMobile) {
    collapsed.value = true;
  }
}

// 初始化时检测一次
if (typeof window !== 'undefined') {
  isMobile.value = window.innerWidth < 768;
  if (isMobile.value) collapsed.value = true;
}

// 监听窗口大小变化
if (typeof window !== 'undefined') {
  window.addEventListener('resize', handleResize);
}

/** 按角色菜单权限递归过滤业务路由（仅保留被分配的目录/页面） */
const filteredRoutes = computed<RouteRecordRaw[]>(() => {
  if (!permissionStore.menuFilterEnabled) return businessRoutes;
  return filterRoutesByMenuPaths(
    businessRoutes,
    new Set(permissionStore.userMenuPaths),
  );
});

/** 从业务路由中提取菜单项 - 使用 ItemGroup 实现分组菜单 */
const menuItems = computed(() => {
  return filteredRoutes.value
    .filter((r) => !r.meta?.hideInMenu)
    .sort((a, b) => ((a.meta?.order as number) ?? 0) - ((b.meta?.order as number) ?? 0))
    .map((routeDef) => buildMenuItem(routeDef));
});

function resolveRoutePath(parentPath: string, routePath: string): string {
  if (routePath.startsWith('/')) return routePath;
  if (!parentPath) return routePath;
  return `${parentPath.replace(/\/$/, '')}/${routePath}`;
}

/**
 * 菜单图标渲染（14 号 §5.4，徽标位置统一）：
 * icon 外包 Badge——展开/折叠两态徽标都在图标右上角（折叠态只渲染 icon，title 槽不渲染，
 * 若 Badge 挂 title 槽折叠后会消失）；count=0 时 antd Badge 不渲染徽标（计数归零自动消失）。
 *
 * 注意（审查意见 P2-3）：count 读取必须留在渲染期（icon 工厂函数内）——依赖在渲染期收集，
 * 计数变化自动触发徽标更新；若移到 buildMenuItem 外层（构建期预求值）会失去响应性。
 */
function renderMenuIcon(iconComp: Component, reminderKey?: string) {
  if (!reminderKey) return h(iconComp);
  return h(
    Badge,
    {
      count: reminderStore.countOf(reminderKey),
      overflowCount: 99,
      size: 'small',
    },
    { default: () => h(iconComp) },
  );
}

function buildMenuItem(routeDef: RouteRecordRaw, parentPath = '') {
  const fullPath = resolveRoutePath(parentPath, routeDef.path);
  const iconComp = routeDef.meta?.icon ? getIconComponent(routeDef.meta.icon as string) : undefined;
  const reminderKey = routeDef.meta?.reminderKey as string | undefined;
  const icon = iconComp ? () => renderMenuIcon(iconComp as Component, reminderKey) : undefined;
  const visibleChildren = routeDef.children?.filter((child) => !child.meta?.hideInMenu) || [];

  if (visibleChildren.length > 0) {
    return h(
      SubMenu,
      { key: fullPath || routeDef.name, icon, title: routeDef.meta?.title || '' },
      {
        default: () => visibleChildren.map((child) => buildMenuItem(child, fullPath)),
      },
    );
  }

  return h(MenuItem, { key: fullPath || routeDef.name, icon }, {
    default: () => routeDef.meta?.title || '',
  });
}

/** 当前选中的菜单项 */
const selectedKeys = computed(() => {
  return [route.path];
});

/** 当前展开的子菜单 */
const openKeys = computed(() => {
  const keys: string[] = [];
  collectOpenKeys(businessRoutes, '', route.path, keys);
  return keys;
});

function collectOpenKeys(
  routeDefs: RouteRecordRaw[],
  parentPath: string,
  currentPath: string,
  keys: string[],
) {
  for (const routeDef of routeDefs) {
    if (routeDef.meta?.hideInMenu) continue;
    const fullPath = resolveRoutePath(parentPath, routeDef.path);
    const visibleChildren = routeDef.children?.filter((child) => !child.meta?.hideInMenu) || [];
    if (visibleChildren.length > 0 && currentPath.startsWith(`${fullPath}/`)) {
      keys.push(fullPath);
      collectOpenKeys(visibleChildren, fullPath, currentPath, keys);
    }
  }
}

/** 菜单点击 - 移动端自动折叠侧边栏 */
function onMenuClick({ key }: { key: string | number }) {
  const keyStr = String(key);
  // 移动端导航后自动折叠侧边栏
  if (isMobile.value) {
    collapsed.value = true;
  }
  // 如果 key 以 / 开头，则是完整路径
  if (keyStr.startsWith('/')) {
    router.push(keyStr);
  } else {
    // 否则可能是路由名称
    router.push({ name: keyStr });
  }
}

// 全局提醒生命周期（14 号 §5.5）：布局挂载即启动 10s 轮询，卸载停止；同时注册语音首次交互预热
onMounted(() => {
  reminderStore.start(10_000);
  setupVoicePreheat();
});

onUnmounted(() => {
  reminderStore.stop();
});

/** 登出 */
async function handleLogout() {
  // 先清提醒状态（停轮询 + 清计数 + 恢复标题），再走登出
  reminderStore.reset();
  await authStore.logout();
}
</script>

<template>
  <Layout class="basic-layout">
    <LayoutSider
      v-model:collapsed="collapsed"
      collapsible
      :width="240"
      :collapsed-width="64"
      theme="light"
      class="layout-sider"
    >
      <div class="logo">
        <h1 v-if="!collapsed">晴多多 平台</h1>
        <h1 v-else>AI</h1>
      </div>
      <!-- 菜单滚动容器：菜单超长时独立滚动，悬停滚动不联动右侧内容 -->
      <div class="menu-scroll">
        <Menu
          :selected-keys="selectedKeys"
          :open-keys="openKeys"
          theme="light"
          mode="inline"
          @click="onMenuClick"
        >
          <component :is="item" v-for="item in menuItems" :key="item.key" />
        </Menu>
      </div>
    </LayoutSider>

    <!-- 移动端遮罩：点击折叠侧边栏 -->
    <div
      v-if="isMobile && !collapsed"
      class="sider-overlay"
      @click="collapsed = true"
    />

    <Layout
      class="layout-right"
      :style="{ marginLeft: isMobile ? '0' : (collapsed ? '64px' : '240px') }"
    >
      <LayoutHeader class="layout-header">
        <div class="header-content">
          <Button
            type="text"
            @click="collapsed = !collapsed"
            class="collapse-btn"
          >
            <MenuUnfoldOutlined v-if="collapsed" />
            <MenuFoldOutlined v-else />
          </Button>

          <div class="header-right">
            <TenantDropdown />
            <HeaderReminder />
            <Dropdown>
              <Button type="text" class="user-btn">
                <Space>
                  <UserOutlined />
                  {{ userStore.nickname || '用户' }}
                </Space>
              </Button>
              <template #overlay>
                <a-menu>
                  <a-menu-item @click="router.push('/profile')">
                    个人设置
                  </a-menu-item>
                  <a-menu-divider />
                  <a-menu-item @click="handleLogout">
                    <LogoutOutlined /> 退出登录
                  </a-menu-item>
                </a-menu>
              </template>
            </Dropdown>
          </div>
        </div>
      </LayoutHeader>

      <LayoutContent class="layout-content">
        <router-view />
      </LayoutContent>
    </Layout>

    <!-- Phase1：闲鱼 AI Agent Chat（只读商品查询，右下角悬浮） -->
  </Layout>
</template>

<style scoped>
.basic-layout {
  min-height: 100vh;
}

.layout-sider {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  z-index: 100;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* Ant LayoutSider 内部包裹层默认为 block，改为 flex 列布局使 .menu-scroll 高度约束生效 */
.layout-sider :deep(.ant-layout-sider-children) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.menu-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.menu-scroll::-webkit-scrollbar {
  width: 6px;
}

.menu-scroll::-webkit-scrollbar-thumb {
  background: var(--color-border);
  border-radius: 3px;
}

.layout-right {
  /* Ant LayoutSider handles its own collapse animation; no additional animation needed here */
}

.logo {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-primary);
  border-bottom: 1px solid var(--color-border-light);
}

.logo h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
}

.layout-header {
  padding: 0;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
  position: sticky;
  top: 0;
  z-index: 99;
}

.header-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 0 16px;
}

.collapse-btn {
  font-size: 16px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-btn {
  font-size: 14px;
}

.layout-content {
  margin: 24px;
  min-height: calc(100vh - 64px - 48px);
  background: var(--color-bg);
}

/* ========== 移动端遮罩层 ========== */
.sider-overlay {
  display: none;
}

/* ========== 响应式: 平板 (768-1023px) ========== */
@media (max-width: 1023px) {
  .layout-content {
    margin: 16px;
  }
}

/* ========== 响应式: 移动端 (<768px) ========== */
@media (max-width: 767px) {
  .layout-sider {
    z-index: 200;
  }

  .sider-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.35);
    z-index: 150;
  }

  .layout-content {
    margin: 12px;
  }

  .header-content {
    padding: 0 12px;
  }
}
</style>
