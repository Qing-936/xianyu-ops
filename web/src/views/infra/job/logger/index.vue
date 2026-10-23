<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Card, Table, Form, Input, Select, Button, Space, Tag } from 'ant-design-vue';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getJobLogPage } from '#/api/infra/job';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'InfraJobLog' });

const loading = ref(false);
const dataSource = ref<any[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ handlerName: '', status: undefined as number | undefined });

const columns = [
  { title: '日志编号', dataIndex: 'id', width: 80 },
  { title: '任务名称', dataIndex: 'handlerName', width: 200 },
  { title: '处理器', dataIndex: 'handlerName', width: 200 },
  { title: '执行次数', dataIndex: 'executeIndex', width: 100 },
  { title: '执行时长', dataIndex: 'duration', width: 100 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '执行时间', dataIndex: 'beginTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getJobLogPage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { handlerName: '', status: undefined }; handleSearch(); }

onMounted(loadData);
</script>

<template>
  <Card title="调度日志">
    <Form layout="inline" class="mb-4" :model="searchForm">
      <Form.Item label="处理器名">
        <Input v-model:value="searchForm.handlerName" placeholder="请输入处理器名" allow-clear />
      </Form.Item>
      <Form.Item label="状态">
        <Select v-model:value="searchForm.status" placeholder="请选择状态" allow-clear style="width: 120px">
          <Select.Option :value="0">成功</Select.Option>
          <Select.Option :value="1">失败</Select.Option>
        </Select>
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
        <template v-if="column.dataIndex === 'status'">
          <Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '成功' : '失败' }}</Tag>
        </template>
      </template>
    </Table>
  </Card>
</template>