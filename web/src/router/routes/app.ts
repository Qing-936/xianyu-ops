import type { RouteRecordRaw } from 'vue-router';

/**
 * 应用管理路由 — 静态菜单定义
 * 开源核心版仅保留货源采集、消息工作台与 Agent。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/app',
    name: 'App',
    meta: {
      icon: 'ant-design:appstore-outlined',
      order: 5,
      title: '应用管理',
    },
    children: [
      {
        name: 'AppXianyu',
        path: 'xianyu',
        meta: {
          icon: 'ant-design:shop-outlined',
          title: '闲鱼',
        },
        children: [
          {
            name: 'AppXianyuSource',
            path: 'source',
            redirect: '/app/xianyu/source/fetch',
            meta: {
              icon: 'ant-design:search-outlined',
              title: '货源管理',
            },
            children: [
              {
                name: 'AppXianyuSourceFetch',
                path: 'fetch',
                component: () => import('#/views/app/xianyu/source/index.vue'),
                meta: {
                  icon: 'ant-design:search-outlined',
                  title: '货源获取',
                },
              },
              {
                name: 'AppXianyuSourceLibrary',
                path: 'library',
                component: () => import('#/views/app/xianyu/source/library/index.vue'),
                meta: {
                  icon: 'ant-design:database-outlined',
                  title: '货源库',
                },
              },
              {
                name: 'AppXianyuSourceBlockedSeller',
                path: 'blocked-seller',
                component: () => import('#/views/app/xianyu/source/blocked-seller/index.vue'),
                meta: {
                  icon: 'ant-design:user-outlined',
                  title: '拉黑卖家管理',
                },
              },
            ],
          },
          {
            name: 'AppXianyuService',
            path: 'service',
            meta: {
              icon: 'ant-design:customer-service-outlined',
              title: '客服消息',
            },
            children: [
              {
                name: 'AppXianyuAgent',
                path: 'agent',
                component: () => import('#/views/app/xianyu/agent/index.vue'),
                meta: {
                  icon: 'ant-design:robot-outlined',
                  title: 'Agent 管理',
                },
              },
              {
                name: 'AppXianyuMessage',
                path: 'message',
                component: () => import('#/views/app/xianyu/message/index.vue'),
                meta: {
                  icon: 'ant-design:message-outlined',
                  title: '消息工作台',
                  reminderKey: 'xianyu-message',
                },
              },
            ],
          },
          {
            name: 'AppConfig',
            path: 'config',
            component: () => import('#/views/app/config/index.vue'),
            meta: {
              icon: 'ant-design:setting-outlined',
              title: '服务配置',
            },
          },
        ],
      },
    ],
  },
];

export default routes;
