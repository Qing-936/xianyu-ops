<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Card, Table, Form, Input, Button, Space, Tooltip } from 'ant-design-vue';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getOperateLogPage } from '#/api/system/operatelog';
import type { SystemOperateLogApi } from '#/api/system/operatelog';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'SystemOperateLog' });

const loading = ref(false);
const dataSource = ref<SystemOperateLogApi.OperateLog[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ type: '', subType: '', action: '' });

const columns = [
  { title: '日志编号', dataIndex: 'id', width: 80 },
  { title: '操作人', dataIndex: 'userName', width: 100 },
  { title: '操作模块', dataIndex: 'type', width: 120 },
  { title: '操作名', dataIndex: 'subType', width: 150 },
  { title: '操作明细', dataIndex: 'action', ellipsis: true },
  { title: '请求地址', dataIndex: 'requestUrl', width: 200, ellipsis: true },
  { title: '操作 IP', dataIndex: 'userIp', width: 130 },
  { title: '操作时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getOperateLogPage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { type: '', subType: '', action: '' }; handleSearch(); }

onMounted(loadData);
</script>

<template>
  <Card title="操作日志">
    <Form layout="inline" class="mb-4" :model="searchForm">
      <Form.Item label="操作模块">
        <Input v-model:value="searchForm.type" placeholder="请输入操作模块" allow-clear />
      </Form.Item>
      <Form.Item label="操作名">
        <Input v-model:value="searchForm.subType" placeholder="请输入操作名" allow-clear />
      </Form.Item>
      <Form.Item label="操作明细">
        <Input v-model:value="searchForm.action" placeholder="请输入操作明细" allow-clear />
      </Form.Item>
      <Form.Item>
        <Space>
          <Button type="primary" @click="handleSearch"><SearchOutlined /> 搜索</Button>
          <Button @click="handleReset"><ReloadOutlined /> 重置</Button>
        </Space>
      </Form.Item>
    </Form>

    <Table :columns="columns" :data-source="dataSource" :loading="loading" :scroll="{ x: 1200 }" :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { pagination.pageNo = p.current; loadData(); }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'action'">
          <Tooltip :title="(record as SystemOperateLogApi.OperateLog).action">{{ (record as SystemOperateLogApi.OperateLog).action || '-' }}</Tooltip>
        </template>
        <template v-if="column.dataIndex === 'requestUrl'">
          <span>{{ (record as SystemOperateLogApi.OperateLog).requestMethod }} {{ (record as SystemOperateLogApi.OperateLog).requestUrl }}</span>
        </template>
      </template>
    </Table>
  </Card>
</template>
