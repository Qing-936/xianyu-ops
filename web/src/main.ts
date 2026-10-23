import { createApp } from 'vue';
import { createPinia } from 'pinia';
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate';
import Antd from 'ant-design-vue';
import App from './App.vue';
import { router } from './router';
import 'ant-design-vue/dist/reset.css';
import './assets/styles/index.css';

async function bootstrap() {
  const app = createApp(App);

  // Pinia 状态管理
  const pinia = createPinia();
  pinia.use(piniaPluginPersistedstate);
  app.use(pinia);

  // Ant Design Vue
  app.use(Antd);

  // 路由
  app.use(router);

  app.mount('#app');
}

bootstrap();
