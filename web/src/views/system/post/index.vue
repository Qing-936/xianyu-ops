<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Tag, Modal, message, Form, Input,
  Select, InputNumber, RadioGroup, Radio,
} from 'ant-design-vue';
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getPostPage, createPost, updatePost, deletePost, getPost } from '#/api/system/post';
import type { SystemPostApi } from '#/api/system/post';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'SystemPost' });

const loading = ref(false);
const dataSource = ref<SystemPostApi.Post[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ name: '', code: '', status: undefined as number | undefined });

const columns = [
  { title: '岗位编号', dataIndex: 'id', width: 80 },
  { title: '岗位编码', dataIndex: 'code', width: 150 },
  { title: '岗位名称', dataIndex: 'name', width: 150 },
  { title: '排序', dataIndex: 'sort', width: 80 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '备注', dataIndex: 'remark', width: 200 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 200 },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getPostPage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { name: '', code: '', status: undefined }; handleSearch(); }

function handleDelete(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该岗位吗？', async onOk() { await deletePost(id); message.success('删除成功'); loadData(); } });
}

onMounted(loadData);

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const modalTitle = computed(() => (formData.value?.id ? '编辑岗位' : '新增岗位'));
const formData = ref<Partial<SystemPostApi.Post>>({});
const FormItem = Form.Item;

function openModal(record?: SystemPostApi.Post) {
  if (record?.id) {
    getPost(record.id!).then((detail) => { formData.value = { ...detail }; modalVisible.value = true; });
  } else {
    formData.value = { status: 0, sort: 0 };
    modalVisible.value = true;
  }
}

async function handleModalOk() {
  modalLoading.value = true;
  try {
    if (formData.value?.id) {
      await updatePost(formData.value as SystemPostApi.Post);
    } else {
      await createPost(formData.value as SystemPostApi.Post);
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
  <Card title="岗位管理">
    <!-- 搜索表单 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <FormItem label="岗位名称">
        <Input v-model:value="searchForm.name" placeholder="请输入岗位名称" allow-clear />
      </FormItem>
      <FormItem label="岗位编码">
        <Input v-model:value="searchForm.code" placeholder="请输入岗位编码" allow-clear />
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
      <Button type="primary" @click="openModal()"><PlusOutlined /> 新增岗位</Button>
    </div>

    <Table :columns="columns" :data-source="dataSource" :loading="loading" :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { pagination.pageNo = p.current; loadData(); }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'status'"><Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '正常' : '停用' }}</Tag></template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="openModal(record as SystemPostApi.Post)">编辑</Button>
            <Button type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal v-model:open="modalVisible" :title="modalTitle" :confirm-loading="modalLoading" width="600px" @ok="handleModalOk" destroy-on-close>
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="岗位名称" required>
          <Input v-model:value="formData.name" placeholder="请输入岗位名称" />
        </FormItem>
        <FormItem label="岗位编码" required>
          <Input v-model:value="formData.code" placeholder="请输入岗位编码" />
        </FormItem>
        <FormItem label="排序">
          <InputNumber v-model:value="formData.sort" :min="0" placeholder="请输入排序" class="w-full" />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="formData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="备注">
          <Input.TextArea v-model:value="formData.remark" placeholder="请输入备注" :rows="3" />
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>