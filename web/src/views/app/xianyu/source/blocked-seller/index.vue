<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { Modal, message } from 'ant-design-vue';
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue';
import {
  getSourceBlockedSellerPage,
  releaseSourceBlockedSeller,
  type SourceBlockedSellerPageReqVO,
  type SourceBlockedSellerRespVO,
} from '#/api/app/xianyu/source';
import { formatDateTime } from '#/utils/format';
import BlockedSellerFormModal from '../components/BlockedSellerFormModal.vue';

defineOptions({ name: 'AppXianyuSourceBlockedSeller' });

// ==================== 常量 ====================

const statusOptions = [
  { value: 1, label: '生效中' },
  { value: 0, label: '已解除' },
];

const statusColorMap: Record<number, string> = {
  1: 'success',
  0: 'default',
};

const statusLabelMap: Record<number, string> = {
  1: '生效中',
  0: '已解除',
};

// ==================== 搜索状态 ====================

const searchForm = reactive({
  sellerName: undefined as string | undefined,
  status: undefined as number | undefined,
});

// ==================== 列表状态 ====================

const loading = ref(false);
const dataSource = ref<SourceBlockedSellerRespVO[]>([]);
const pagination = reactive({ current: 1, pageSize: 10, total: 0 });
const releasingId = ref<number | null>(null);

const formModalRef = ref<InstanceType<typeof BlockedSellerFormModal> | null>(null);

// ==================== 表格列定义 ====================

const columns = [
  { title: '卖家名称', key: 'sellerName', width: 180, ellipsis: true },
  { title: '卖家ID', key: 'sellerId', width: 160, ellipsis: true },
  { title: '拉黑原因', key: 'blockReason', width: 240, ellipsis: true },
  { title: '来源任务', key: 'sourceTaskId', width: 120 },
  { title: '拉黑时间', key: 'blockTime', width: 180 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'action', width: 140, fixed: 'right' as const },
];

// ==================== 数据加载 ====================

async function loadData() {
  loading.value = true;
  try {
    const params: SourceBlockedSellerPageReqVO = {
      pageNo: pagination.current,
      pageSize: pagination.pageSize,
      sellerName: searchForm.sellerName || undefined,
      status: searchForm.status,
    };
    const res = await getSourceBlockedSellerPage(params);
    dataSource.value = res.list || [];
    pagination.total = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.current = 1;
  loadData();
}

function handleReset() {
  searchForm.sellerName = undefined;
  searchForm.status = undefined;
  pagination.current = 1;
  loadData();
}

function handleTableChange(pag: { current?: number; pageSize?: number }) {
  pagination.current = pag.current || 1;
  pagination.pageSize = pag.pageSize || 10;
  loadData();
}

// ==================== 新增 / 编辑 ====================

function handleCreate() {
  formModalRef.value?.openCreate();
}

function handleEdit(record: SourceBlockedSellerRespVO) {
  formModalRef.value?.openEdit(record);
}

// ==================== 解除 ====================

function handleRelease(record: SourceBlockedSellerRespVO) {
  Modal.confirm({
    title: '解除拉黑',
    content: `确认解除对卖家「${record.sellerName || record.sellerId || '-'}」的拉黑？解除后该卖家将重新进入采集结果。`,
    okText: '确认解除',
    cancelText: '取消',
    onOk: async () => {
      releasingId.value = record.id;
      try {
        await releaseSourceBlockedSeller(record.id);
        message.success('已解除拉黑');
        await loadData();
      } finally {
        releasingId.value = null;
      }
    },
  });
}

// ==================== 初始化 ====================

onMounted(() => {
  void loadData();
});
</script>

<template>
  <div>
    <!-- 搜索区 + 新增按钮 -->
    <div :style="{ background: '#f5f5f5', borderRadius: '8px', padding: '16px', marginBottom: '16px' }">
      <div style="display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 8px;">
        <a-form layout="inline" :model="searchForm">
          <a-form-item label="卖家名称">
            <a-input
              v-model:value="searchForm.sellerName"
              placeholder="请输入卖家名称/昵称"
              allow-clear
              style="width: 200px"
            />
          </a-form-item>
          <a-form-item label="状态">
            <a-select
              v-model:value="searchForm.status"
              placeholder="请选择状态"
              allow-clear
              :options="statusOptions"
              style="width: 120px"
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="handleSearch">
                <SearchOutlined /> 搜索
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined /> 重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
        <a-button type="primary" @click="handleCreate">
          <PlusOutlined /> 新增拉黑
        </a-button>
      </div>
    </div>

    <!-- 表格 -->
    <a-table
      :columns="columns"
      :data-source="dataSource"
      :loading="loading"
      row-key="id"
      :scroll="{ x: 1100 }"
      :pagination="{
        current: pagination.current,
        pageSize: pagination.pageSize,
        total: pagination.total,
        showSizeChanger: true,
        showTotal: (total: number) => `共 ${total} 条`,
      }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <!-- 卖家名称 -->
        <template v-if="column.key === 'sellerName'">
          {{ (record as SourceBlockedSellerRespVO).sellerName || '-' }}
        </template>

        <!-- 卖家ID -->
        <template v-if="column.key === 'sellerId'">
          {{ (record as SourceBlockedSellerRespVO).sellerId || '-' }}
        </template>

        <!-- 拉黑原因 -->
        <template v-if="column.key === 'blockReason'">
          {{ (record as SourceBlockedSellerRespVO).blockReason || '-' }}
        </template>

        <!-- 来源任务 -->
        <template v-if="column.key === 'sourceTaskId'">
          <a-tag v-if="(record as SourceBlockedSellerRespVO).sourceTaskId" color="blue">
            任务#{{ (record as SourceBlockedSellerRespVO).sourceTaskId }}
          </a-tag>
          <span v-else>-</span>
        </template>

        <!-- 拉黑时间 -->
        <template v-if="column.key === 'blockTime'">
          {{ formatDateTime((record as SourceBlockedSellerRespVO).blockTime) }}
        </template>

        <!-- 状态 -->
        <template v-if="column.key === 'status'">
          <a-tag :color="statusColorMap[(record as SourceBlockedSellerRespVO).status] ?? 'default'">
            {{ statusLabelMap[(record as SourceBlockedSellerRespVO).status] ?? (record as SourceBlockedSellerRespVO).status }}
          </a-tag>
        </template>

        <!-- 操作 -->
        <template v-if="column.key === 'action'">
          <a-space size="small">
            <a-button type="link" size="small" @click="handleEdit(record as SourceBlockedSellerRespVO)">
              编辑
            </a-button>
            <a-button
              v-if="(record as SourceBlockedSellerRespVO).status === 1"
              type="link"
              size="small"
              danger
              :loading="releasingId === (record as SourceBlockedSellerRespVO).id"
              @click="handleRelease(record as SourceBlockedSellerRespVO)"
            >
              解除
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑弹窗 -->
    <BlockedSellerFormModal ref="formModalRef" @success="loadData" />
  </div>
</template>
