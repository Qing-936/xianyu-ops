<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Tag, Modal, message, Form, Input,
  Select, RadioGroup, Radio,
} from 'ant-design-vue';
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getNoticePage, createNotice, updateNotice, deleteNotice, getNotice } from '#/api/system/notice';
import type { SystemNoticeApi } from '#/api/system/notice';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'SystemNotice' });

const loading = ref(false);
const dataSource = ref<SystemNoticeApi.Notice[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ title: '', status: undefined as number | undefined, type: undefined as number | undefined });

const columns = [
  { title: '公告编号', dataIndex: 'id', width: 80 },
  { title: '公告标题', dataIndex: 'title', width: 250 },
  { title: '公告类型', dataIndex: 'type', width: 100 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 180 },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getNoticePage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { title: '', status: undefined, type: undefined }; handleSearch(); }

function handleDelete(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该公告吗？', async onOk() { await deleteNotice(id); message.success('删除成功'); loadData(); } });
}

onMounted(loadData);

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const modalTitle = computed(() => (formData.value?.id ? '编辑公告' : '新增公告'));
const formData = ref<Partial<SystemNoticeApi.Notice>>({});
const FormItem = Form.Item;

function openModal(record?: SystemNoticeApi.Notice) {
  if (record?.id) {
    getNotice(record.id!).then((detail) => { formData.value = { ...detail }; modalVisible.value = true; });
  } else {
    formData.value = { status: 0, type: 1 };
    modalVisible.value = true;
  }
}

async function handleModalOk() {
  modalLoading.value = true;
  try {
    if (formData.value?.id) {
      await updateNotice(formData.value as SystemNoticeApi.Notice);
    } else {
      await createNotice(formData.value as SystemNoticeApi.Notice);
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
  <Card title="通知公告">
    <!-- 搜索表单 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <FormItem label="公告标题">
        <Input v-model:value="searchForm.title" placeholder="请输入公告标题" allow-clear />
      </FormItem>
      <FormItem label="公告类型">
        <Select v-model:value="searchForm.type" placeholder="请选择公告类型" allow-clear style="width: 130px">
          <Select.Option :value="1">通知</Select.Option>
          <Select.Option :value="2">公告</Select.Option>
        </Select>
      </FormItem>
      <FormItem label="状态">
        <Select v-model:value="searchForm.status" placeholder="请选择状态" allow-clear style="width: 120px">
          <Select.Option :value="0">正常</Select.Option>
          <Select.Option :value="1">停用</Select.Option>
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
      <Button type="primary" @click="openModal()"><PlusOutlined /> 新增公告</Button>
    </div>

    <Table :columns="columns" :data-source="dataSource" :loading="loading" :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { pagination.pageNo = p.current; loadData(); }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'type'"><Tag>{{ record.type === 1 ? '通知' : '公告' }}</Tag></template>
        <template v-if="column.dataIndex === 'status'"><Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '正常' : '停用' }}</Tag></template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="openModal(record as SystemNoticeApi.Notice)">编辑</Button>
            <Button type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal v-model:open="modalVisible" :title="modalTitle" :confirm-loading="modalLoading" width="700px" @ok="handleModalOk" destroy-on-close>
      <Form :label-col="{ span: 4 }" :wrapper-col="{ span: 18 }" class="mt-4">
        <FormItem label="公告标题" required>
          <Input v-model:value="formData.title" placeholder="请输入公告标题" />
        </FormItem>
        <FormItem label="公告类型" required>
          <RadioGroup v-model:value="formData.type">
            <Radio :value="1">通知</Radio>
            <Radio :value="2">公告</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="公告内容" required>
          <Input.TextArea v-model:value="formData.content" placeholder="请输入公告内容" :rows="6" />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="formData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>