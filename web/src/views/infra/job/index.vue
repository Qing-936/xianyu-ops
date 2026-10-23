<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Tag, Modal, message, Form, Input,
  Select, InputNumber, RadioGroup, Radio,
} from 'ant-design-vue';
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getJobPage, createJob, updateJob, deleteJob, triggerJob, updateJobStatus, getJob } from '#/api/infra/job';
import type { InfraJobApi } from '#/api/infra/job';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'InfraJob' });

const loading = ref(false);
const dataSource = ref<InfraJobApi.Job[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ name: '', handlerName: '', status: undefined as number | undefined });

const columns = [
  { title: '任务编号', dataIndex: 'id', width: 80 },
  { title: '任务名称', dataIndex: 'name', width: 200 },
  { title: '处理器', dataIndex: 'handlerName', width: 200 },
  { title: 'Cron表达式', dataIndex: 'cronExpression', width: 130 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 350 },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getJobPage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { name: '', handlerName: '', status: undefined }; handleSearch(); }

function handleDelete(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该任务吗？', async onOk() { await deleteJob(id); message.success('删除成功'); loadData(); } });
}

async function handleTrigger(id: number) {
  try {
    await triggerJob(id);
    message.success('执行成功');
  } catch { /* ignore */ }
}

/** 任务状态枚举：与后端 JobStatusEnum 对齐 */
const JOB_STATUS = { INIT: 0, NORMAL: 1, STOP: 2 } as const;

function handleStatusChange(record: InfraJobApi.Job) {
  const newStatus = record.status === JOB_STATUS.NORMAL ? JOB_STATUS.STOP : JOB_STATUS.NORMAL;
  const statusText = newStatus === JOB_STATUS.NORMAL ? '开启' : '暂停';
  Modal.confirm({
    title: `确认${statusText}`,
    content: `确定${statusText}任务「${record.name}」吗？`,
    async onOk() {
      await updateJobStatus(record.id!, newStatus);
      message.success('修改成功');
      loadData();
    },
  });
}

onMounted(loadData);

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const modalTitle = computed(() => (formData.value?.id ? '编辑任务' : '新增任务'));
const formData = ref<Partial<InfraJobApi.Job>>({});
const FormItem = Form.Item;

function openModal(record?: InfraJobApi.Job) {
  if (record?.id) {
    getJob(record.id!).then((detail) => { formData.value = { ...detail }; modalVisible.value = true; });
  } else {
    formData.value = { status: JOB_STATUS.NORMAL, retryCount: 0, retryInterval: 0, monitorTimeout: 0 };
    modalVisible.value = true;
  }
}

async function handleModalOk() {
  modalLoading.value = true;
  try {
    if (formData.value?.id) {
      await updateJob(formData.value as InfraJobApi.Job);
    } else {
      await createJob(formData.value as InfraJobApi.Job);
    }
    message.success('操作成功');
    modalVisible.value = false;
    loadData();
  } finally {
    modalLoading.value = false;
  }
}
</script>

<template>
  <Card title="定时任务">
    <!-- 搜索 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <FormItem label="任务名称">
        <Input v-model:value="searchForm.name" placeholder="请输入任务名称" allow-clear />
      </FormItem>
      <FormItem label="处理器名">
        <Input v-model:value="searchForm.handlerName" placeholder="请输入处理器名" allow-clear />
      </FormItem>
      <FormItem label="状态">
        <Select v-model:value="searchForm.status" placeholder="请选择状态" allow-clear style="width: 120px">
          <Select.Option :value="JOB_STATUS.NORMAL">开启</Select.Option>
          <Select.Option :value="JOB_STATUS.STOP">暂停</Select.Option>
        </Select>
      </FormItem>
      <FormItem>
        <Space>
          <Button type="primary" @click="handleSearch"><SearchOutlined /> 搜索</Button>
          <Button @click="handleReset"><ReloadOutlined /> 重置</Button>
        </Space>
      </FormItem>
    </Form>

    <div class="mb-4">
      <Button type="primary" @click="openModal()"><PlusOutlined /> 新增任务</Button>
    </div>

    <Table :columns="columns" :data-source="dataSource" :loading="loading" :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { pagination.pageNo = p.current; loadData(); }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'status'">
          <Tag :color="record.status === JOB_STATUS.NORMAL ? 'green' : 'red'">{{ record.status === JOB_STATUS.NORMAL ? '开启' : '暂停' }}</Tag>
        </template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="openModal(record as InfraJobApi.Job)">编辑</Button>
            <Button type="link" size="small" @click="handleStatusChange(record as InfraJobApi.Job)">{{ (record as InfraJobApi.Job).status === JOB_STATUS.NORMAL ? '暂停' : '开启' }}</Button>
            <Button type="link" size="small" @click="handleTrigger(record.id)">执行</Button>
            <Button type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal v-model:open="modalVisible" :title="modalTitle" :confirm-loading="modalLoading" width="600px" @ok="handleModalOk" destroy-on-close>
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="任务名称" required>
          <Input v-model:value="formData.name" placeholder="请输入任务名称" />
        </FormItem>
        <FormItem label="处理器名" required>
          <Input v-model:value="formData.handlerName" placeholder="请输入处理器名" />
        </FormItem>
        <FormItem label="Cron表达式" required>
          <Input v-model:value="formData.cronExpression" placeholder="请输入Cron表达式" />
        </FormItem>
        <FormItem label="重试次数">
          <InputNumber v-model:value="formData.retryCount" :min="0" placeholder="请输入重试次数" class="w-full" />
        </FormItem>
        <FormItem label="重试间隔">
          <InputNumber v-model:value="formData.retryInterval" :min="0" placeholder="请输入重试间隔(ms)" class="w-full" />
        </FormItem>
        <FormItem label="监控超时">
          <InputNumber v-model:value="formData.monitorTimeout" :min="0" placeholder="请输入监控超时(ms)" class="w-full" />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="formData.status">
            <Radio :value="JOB_STATUS.NORMAL">开启</Radio>
            <Radio :value="JOB_STATUS.STOP">暂停</Radio>
          </RadioGroup>
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>