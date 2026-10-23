import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers';
import AutoImport from 'unplugin-auto-import/vite';
import { resolve } from 'node:path';

export default defineConfig(({ mode }) => {
  return {
    plugins: [
      vue(),
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        dts: 'src/auto-imports.d.ts',
      }),
      Components({
        resolvers: [
          AntDesignVueResolver({
            importStyle: false,
          }),
        ],
        dts: 'src/components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '#': resolve(__dirname, 'src'),
      },
    },
    server: {
      port: 5666,
      watch: {
        // 忽略独立子项目（h5 / h5-xianyu），避免其文件变更触发管理端清缓存全量 reload
        ignored: ['**/h5/**', '**/h5-xianyu/**'],
      },
      proxy: {
        '/admin-api': {
          target: 'http://localhost:48180/admin-api',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/admin-api/, ''),
        },
        // Phase1：AI Agent（本地开发代理；生产环境由 Nginx /agent-api/ 反代）
        '/agent-api': {
          target: 'http://127.0.0.1:8010',
          changeOrigin: true,
        },
      },
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            antd: ['ant-design-vue', '@ant-design/icons-vue'],
          },
        },
      },
    },
  };
});
