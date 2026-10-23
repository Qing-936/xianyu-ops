/// <reference types="vite/client" />

import 'vue-router';

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<{}, {}, any>;
  export default component;
}

/** 扩展 vue-router 的 RouteMeta 类型 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 菜单标题 */
    title?: string;
    /** 图标标识 */
    icon?: string;
    /** 排序号 */
    order?: number;
    /** 是否在菜单中隐藏 */
    hideInMenu?: boolean;
    /** 是否在面包屑中隐藏 */
    hideInBreadcrumb?: boolean;
    /** 是否在标签页中隐藏 */
    hideInTab?: boolean;
    /** 是否已加载 */
    loaded?: boolean;
  }
}

interface ImportMetaEnv {
  readonly VITE_APP_TITLE: string;
  readonly VITE_APP_NAMESPACE: string;
  readonly VITE_APP_TENANT_ENABLE: string;
  readonly VITE_APP_TENANT_ID: string;
  readonly VITE_APP_CAPTCHA_ENABLE: string;
  readonly VITE_PORT: string;
  readonly VITE_BASE: string;
  readonly VITE_BASE_URL: string;
  readonly VITE_GLOB_API_URL: string;
  readonly VITE_ROUTER_HISTORY: string;
  readonly VITE_APP_DEFAULT_USERNAME: string;
  readonly VITE_APP_DEFAULT_PASSWORD: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
