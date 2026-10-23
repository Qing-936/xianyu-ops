<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Card, Table, Form, Input, Select, Button, Space, Tag } from 'ant-design-vue';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getLoginLogPage } from '#/api/system/loginlog';
import type { SystemLoginLogApi } from '#/api/system/loginlog';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'SystemLoginLog' });

/** 登录日志类型：与后端 LoginLogTypeEnum 对齐 */
const LOG_TYPE_MAP: Record<number, string> = {
  100: '账号登录',
  101: '社交登录',
  103: '手机登录',
  104: '短信登录',
  200: '主动登出',
  202: '强制退出',
};
const logTypeOptions = Object.entries(LOG_TYPE_MAP).map(([value, label]) => ({ label, value: Number(value) }));

/** 登录结果：与后端 LoginResultEnum 对齐 */
const RESULT_MAP: Record<number, string> = {
  0: '成功',
  10: '账号或密码不正确',
  20: '用户被禁用',
  30: '验证码不存在',
  31: '验证码不正确',
};
/** 后端分页接口的 status 参数为 Boolean：true=仅成功 false=仅失败；前端用 1/0 承载再转换 */
const statusOptions = [
  { label: '成功', value: 1 },
  { label: '失败', value: 0 },
];

const loading = ref(false);
const dataSource = ref<SystemLoginLogApi.LoginLog[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ username: '', userIp: '', status: undefined as number | undefined, logType: undefined as number | undefined });

const columns = [
  { title: '日志编号', dataIndex: 'id', width: 80 },
  { title: '用户名', dataIndex: 'username', width: 120 },
  { title: '登录类型', dataIndex: 'logType', width: 100 },
  { title: '登录IP', dataIndex: 'userIp', width: 130 },
  { title: '结果', dataIndex: 'result', width: 160 },
  { title: '登录时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
];

async function loadData() {
  loading.value = true;
  try {
    // logType 仅前端展示过滤用，后端分页接口不支持；status 1/0 转 Boolean
    const { logType, status, ...rest } = { ...pagination.value, ...searchForm.value };
    const res = await getLoginLogPage({ ...rest, status: status === undefined ? undefined : status === 1 });
    dataSource.value = (res.list || []).filter((item) => logType === undefined || item.logType === logType);
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { username: '', userIp: '', status: undefined, logType: undefined }; handleSearch(); }

onMounted(loadData);
</script>

<template>
  <Card title="登录日志">
    <Form layout="inline" class="mb-4" :model="searchForm">
      <Form.Item label="用户名">
        <Input v-model:value="searchForm.username" placeholder="请输入用户名" allow-clear />
      </Form.Item>
      <Form.Item label="登录IP">
        <Input v-model:value="searchForm.userIp" placeholder="请输入登录IP" allow-clear />
      </Form.Item>
      <Form.Item label="登录类型">
        <Select v-model:value="searchForm.logType" placeholder="请选择" allow-clear style="width: 130px" :options="logTypeOptions" />
      </Form.Item>
      <Form.Item label="结果">
        <Select v-model:value="searchForm.status" placeholder="请选择" allow-clear style="width: 120px" :options="statusOptions" />
      </Form.Item>
      <Form.Item>
        <Space>
          <Button type="primary" @click="handleSearch"><SearchOutlined /> 搜索</Button>
          <Button @click="handleReset"><ReloadOutlined /> 重置</Button>
        </Space>
      </Form.Item>
    </Form>

    <Table :columns="columns" :data-source="dataSource" :loading="loading" :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { pagination.pageNo = p.current; loadData(); }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'logType'">
          {{ LOG_TYPE_MAP[(record as SystemLoginLogApi.LoginLog).logType] || (record as SystemLoginLogApi.LoginLog).logType }}
        </template>
        <template v-if="column.dataIndex === 'result'">
          <Tag :color="(record as SystemLoginLogApi.LoginLog).result === 0 ? 'green' : 'red'">
            {{ RESULT_MAP[(record as SystemLoginLogApi.LoginLog).result] || '失败' }}
          </Tag>
        </template>
      </template>
    </Table>
  </Card>
</template>
