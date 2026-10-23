<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Modal, message, Form, Input,
  Select, Popconfirm, Upload,
} from 'ant-design-vue';
import { SearchOutlined, ReloadOutlined, UploadOutlined, PlusOutlined } from '@ant-design/icons-vue';
import { getFilePage, deleteFile } from '#/api/infra/file';
import type { InfraFileApi } from '#/api/infra/file';
import { formatDateTime } from '#/utils/format';
import { useAuthStore } from '#/store/auth';
import { applyTenantHeaders } from '#/utils/tenant';

defineOptions({ name: 'InfraFile' });

/** 文件下载 */
function handleDownload(url: string) {
  window.open(url);
}

const authStore = useAuthStore();

/** 获取上传请求头（租户头与 axios 拦截器共用同一注入逻辑） */
const uploadHeaders = computed(() => {
  const headers: Record<string, string> = {};
  applyTenantHeaders(headers);
  headers.Authorization = `Bearer ${authStore.accessToken || ''}`;
  return headers;
});


const loading = ref(false);
const dataSource = ref<InfraFileApi.File[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ path: '', type: '' });

const columns = [
  { title: '文件编号', dataIndex: 'id', width: 80 },
  { title: '文件名', dataIndex: 'name', width: 200 },
  { title: '文件路径', dataIndex: 'path', width: 250 },
  { title: '文件URL', dataIndex: 'url', width: 300 },
  { title: '文件大小', dataIndex: 'size', width: 100 },
  { title: '文件类型', dataIndex: 'type', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 120 },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getFilePage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { path: '', type: '' }; handleSearch(); }

function handleDelete(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该文件吗？', async onOk() { await deleteFile(id); message.success('删除成功'); loadData(); } });
}

onMounted(loadData);

// ============ 文件上传 ============
const uploadModalVisible = ref(false);
const uploadLoading = ref(false);

function handleUpload(info: any) {
  if (info.file.status === 'uploading') {
    uploadLoading.value = true;
  }
  if (info.file.status === 'done') {
    uploadLoading.value = false;
    message.success('上传成功');
    uploadModalVisible.value = false;
    loadData();
  } else if (info.file.status === 'error') {
    uploadLoading.value = false;
    message.error('上传失败');
  }
}

/** 格式化文件大小 */
function formatSize(bytes: number) {
  if (!bytes) return '-';
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}
</script>

<template>
  <Card title="文件管理">
    <!-- 搜索 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <Form.Item label="文件路径">
        <Input v-model:value="searchForm.path" placeholder="请输入文件路径" allow-clear />
      </Form.Item>
      <Form.Item label="文件类型">
        <Input v-model:value="searchForm.type" placeholder="请输入文件类型" allow-clear />
      </Form.Item>
      <Form.Item>
        <Space>
          <Button type="primary" @click="handleSearch"><SearchOutlined /> 搜索</Button>
          <Button @click="handleReset"><ReloadOutlined /> 重置</Button>
        </Space>
      </Form.Item>
    </Form>

    <div class="mb-4">
      <Button type="primary" @click="uploadModalVisible = true"><UploadOutlined /> 上传文件</Button>
    </div>

    <Table :columns="columns" :data-source="dataSource" :loading="loading" :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { pagination.pageNo = p.current; loadData(); }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'size'">{{ formatSize(record.size) }}</template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="handleDownload(record.url)">下载</Button>
            <Popconfirm title="确定要删除该文件吗？" @confirm="handleDelete(record.id)">
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 上传文件 Modal -->
    <Modal v-model:open="uploadModalVisible" title="上传文件" :footer="null" width="500px" destroy-on-close>
      <Upload
        name="file"
        :action="'/admin-api/infra/file/upload'"
        :headers="uploadHeaders"
        @change="handleUpload"
      >
        <Button :loading="uploadLoading"><PlusOutlined /> 选择文件</Button>
      </Upload>
    </Modal>
  </Card>
</template>