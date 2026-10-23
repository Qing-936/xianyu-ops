import type { RouteRecordRaw } from 'vue-router';

/**
 * 基础设施路由 — 静态菜单定义
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/infra',
    name: 'Infra',
    meta: {
      icon: 'ant-design:tool-outlined',
      order: 20,
      title: '基础设施',
    },
    children: [
      {
        name: 'InfraFile',
        path: 'file',
        component: () => import('#/views/infra/file/index.vue'),
        meta: {
          icon: 'ant-design:folder-outlined',
          title: '文件管理',
        },
      },
      {
        name: 'InfraJob',
        path: 'job',
        component: () => import('#/views/infra/job/index.vue'),
        meta: {
          icon: 'ant-design:clock-circle-outlined',
          title: '定时任务',
        },
      },
      {
        name: 'InfraJobLog',
        path: 'job-log',
        component: () => import('#/views/infra/job/logger/index.vue'),
        meta: {
          icon: 'ant-design:history-outlined',
          title: '调度日志',
          hideInMenu: true,
          activePath: '/infra/job',
        },
      },
    ],
  },
];

export default routes;
