<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue';
import { message } from 'ant-design-vue';
import { SearchOutlined, ReloadOutlined, PlusOutlined } from '@ant-design/icons-vue';
import QRCode from 'qrcode';
import {
  getSourceCookieRefreshStatus,
  getSourceTaskPage,
  deleteSourceTask,
  pauseSourceTask,
  refreshSourceCookieQr,
  resumeSourceTask,
  executeSourceTask,
  exportSourceTaskCsv,
  type SourceCookieRefreshStatusRespVO,
  type SourceTaskRespVO,
  type SourceTaskPageReqVO,
} from '#/api/app/xianyu/source';
import { fenToYuan, formatDateTime } from '#/utils/format';
import CreateTaskModal from './components/CreateTaskModal.vue';
import TaskResultModal from './components/TaskResultModal.vue';

defineOptions({ name: 'AppXianyuSourceFetch' });

// ==================== 常量 ====================

const statusOptions = [
  { value: 0, label: '运行中' },
  { value: 1, label: '已暂停' },
  { value: 2, label: '已完成' },
  { value: 3, label: '提交失败' },
  { value: 4, label: '失败' },
];

const triggerModeOptions = [
  { value: 0, label: '一次性' },
  { value: 1, label: '每日定时' },
];

const statusColorMap: Record<number, string> = {
  0: 'processing',
  1: 'warning',
  2: 'success',
  3: 'error',
  4: 'error',
};

const statusLabelMap: Record<number, string> = {
  0: '运行中',
  1: '已暂停',
  2: '已完成',
  3: '提交失败',
  4: '失败',
};

// ==================== 搜索状态 ====================

const searchForm = reactive({
  productName: undefined as string | undefined,
  status: undefined as number | undefined,
  triggerMode: undefined as number | undefined,
});

// ==================== 列表状态 ====================

const loading = ref(false);
const dataSource = ref<SourceTaskRespVO[]>([]);
const pagination = reactive({ current: 1, pageSize: 10, total: 0 });
let taskPageLoading = false;
let taskAutoRefreshTimer: number | null = null;
const retryingTaskId = ref<number | null>(null);
const retryBaselineExecutedCount = ref<number | null>(null);
const deletingTaskId = ref<number | null>(null);

function clearRetryState() {
  retryingTaskId.value = null;
  retryBaselineExecutedCount.value = null;
}

function reconcileRetryState(tasks: SourceTaskRespVO[]) {
  if (retryingTaskId.value === null || retryBaselineExecutedCount.value === null) return;
  const task = tasks.find((item) => item.id === retryingTaskId.value);
  if (task && task.executedCount > retryBaselineExecutedCount.value) {
    clearRetryState();
  }
}

// ==================== 子组件引用 ====================

const createTaskModalRef = ref<InstanceType<typeof CreateTaskModal> | null>(null);
const taskResultModalRef = ref<InstanceType<typeof TaskResultModal> | null>(null);

// ==================== Cookie 刷新 ====================

const cookieRefreshVisible = ref(false);
const cookieRefreshLoading = ref(false);
const cookieRefreshQrLoading = ref(false);
const cookieRefreshSessionId = ref('');
const cookieRefreshQrDataUrl = ref('');
const cookieRefreshStatus = ref('pending');
const cookieRefreshMessage = ref('');
let cookieRefreshTimer: number | null = null;

// ==================== 表格列定义 ====================

const columns = [
  { title: '商品名称', dataIndex: 'productName', width: 180 },
  { title: '价格范围', key: 'priceRange', width: 180 },
  { title: '获取条数', dataIndex: 'fetchCount', width: 90 },
  { title: '已执行次数', dataIndex: 'executedCount', width: 100 },
  { title: '执行方式', key: 'triggerMode', width: 150 },
  { title: '任务状态', key: 'status', width: 100 },
  { title: '创建时间', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 328, fixed: 'right' as const },
];

// ==================== 数据加载 ====================

async function loadData(showLoading = true) {
  if (taskPageLoading) {
    return;
  }
  taskPageLoading = true;
  if (showLoading) {
    loading.value = true;
  }
  try {
    const params: SourceTaskPageReqVO = {
      pageNo: pagination.current,
      pageSize: pagination.pageSize,
      productName: searchForm.productName || undefined,
      status: searchForm.status,
      triggerMode: searchForm.triggerMode,
    };
    const res = await getSourceTaskPage(params);
    const tasks = res.list || [];
    reconcileRetryState(tasks);
    dataSource.value = tasks;
    pagination.total = res.total || 0;
  } finally {
    taskPageLoading = false;
    if (showLoading) {
      loading.value = false;
    }
  }
}

function stopTaskAutoRefresh() {
  if (taskAutoRefreshTimer !== null) {
    window.clearInterval(taskAutoRefreshTimer);
    taskAutoRefreshTimer = null;
  }
}

function startTaskAutoRefresh() {
  stopTaskAutoRefresh();
  taskAutoRefreshTimer = window.setInterval(() => {
    void loadData(false);
  }, 10000);
}

function handleSearch() {
  pagination.current = 1;
  loadData();
}

function handleReset() {
  searchForm.productName = undefined;
  searchForm.status = undefined;
  searchForm.triggerMode = undefined;
  pagination.current = 1;
  loadData();
}

function handleTableChange(pag: { current?: number; pageSize?: number }) {
  pagination.current = pag.current || 1;
  pagination.pageSize = pag.pageSize || 10;
  loadData();
}

// ==================== 价格范围展示 ====================

function formatPriceRange(record: SourceTaskRespVO): string {
  const hasMin = record.filterMinPrice !== undefined && record.filterMinPrice !== null;
  const hasMax = record.filterMaxPrice !== undefined && record.filterMaxPrice !== null;
  if (!hasMin && !hasMax) return '-';
  const minStr = hasMin ? `¥${fenToYuan(record.filterMinPrice)}` : '-';
  const maxStr = hasMax ? `¥${fenToYuan(record.filterMaxPrice)}` : '-';
  return `${minStr} ~ ${maxStr}`;
}

// ==================== 暂停 ====================

const pausingId = ref<number | null>(null);

async function handlePause(record: SourceTaskRespVO) {
  pausingId.value = record.id;
  try {
    await pauseSourceTask(record.id);
    message.success('任务已暂停');
    loadData();
  } finally {
    pausingId.value = null;
  }
}

// ==================== 恢复 ====================

const resumingId = ref<number | null>(null);

async function handleResume(record: SourceTaskRespVO) {
  resumingId.value = record.id;
  try {
    await resumeSourceTask(record.id);
    message.success('任务已恢复');
    loadData();
  } finally {
    resumingId.value = null;
  }
}

// ==================== 下载文件 ====================

const downloadingId = ref<number | null>(null);

async function handleDownload(record: SourceTaskRespVO) {
  downloadingId.value = record.id;
  try {
    const resp = await exportSourceTaskCsv(record.id);
    // 响应拦截器对 blob 返回完整 AxiosResponse，需取 .data
    const blob = (resp as any)?.data instanceof Blob ? (resp as any).data : resp;
    // 检查是否为错误响应（后端返回 JSON 错误信息被包装成 blob）
    if (blob instanceof Blob && blob.type?.includes('application/json')) {
      const text = await blob.text();
      const err = JSON.parse(text);
      message.error(err.msg || '下载失败');
      return;
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `货源数据_${record.productName}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  } catch {
    message.error('下载失败，请重试');
  } finally {
    downloadingId.value = null;
  }
}

// ==================== 立即执行 ====================

async function handleExecute(record: SourceTaskRespVO) {
  if (retryingTaskId.value !== null) return;
  retryingTaskId.value = record.id;
  retryBaselineExecutedCount.value = record.executedCount;
  try {
    await executeSourceTask(record.id);
  } catch {
    clearRetryState();
    return;
  }
  await loadData();
  message.success('已提交重试');
}

// ==================== 查看结果 ======================================

function handleViewResult(record: SourceTaskRespVO) {
  taskResultModalRef.value?.open(record.id);
}

async function handleDelete(record: SourceTaskRespVO) {
  deletingTaskId.value = record.id;
  try {
    await deleteSourceTask(record.id);
    message.success('任务及所有批次的采集结果已永久删除，已入库商品已保留');
    await loadData();
  } finally {
    deletingTaskId.value = null;
  }
}

// ==================== 创建任务 ====================

function handleCreate() {
  createTaskModalRef.value?.open();
}

function stopCookieRefreshPolling() {
  if (cookieRefreshTimer !== null) {
    window.clearInterval(cookieRefreshTimer);
    cookieRefreshTimer = null;
  }
}

async function pollCookieRefreshStatus() {
  if (!cookieRefreshSessionId.value) {
    return;
  }
  try {
    const status = await getSourceCookieRefreshStatus(cookieRefreshSessionId.value);
    applyCookieRefreshStatus(status);
  } catch (error) {
    stopCookieRefreshPolling();
    cookieRefreshStatus.value = 'failed';
    cookieRefreshMessage.value = '扫码状态查询失败，请重新生成二维码';
    console.error(error);
  }
}

function applyCookieRefreshStatus(status: SourceCookieRefreshStatusRespVO) {
  cookieRefreshStatus.value = status.status;
  cookieRefreshMessage.value = status.message;
  if (status.status === 'success') {
    stopCookieRefreshPolling();
    handleCloseCookieRefreshModal();
    message.success('Cookie 刷新成功，已回写共享池');
  } else if (status.status === 'failed' || status.status === 'expired') {
    stopCookieRefreshPolling();
  }
}

function startCookieRefreshPolling() {
  stopCookieRefreshPolling();
  cookieRefreshTimer = window.setInterval(() => {
    void pollCookieRefreshStatus();
  }, 3000);
}

async function handleRefreshCookie() {
  cookieRefreshLoading.value = true;
  cookieRefreshQrLoading.value = true;
  cookieRefreshStatus.value = 'pending';
  cookieRefreshMessage.value = '正在生成二维码...';
  cookieRefreshQrDataUrl.value = '';
  try {
    const refresh = await refreshSourceCookieQr();
    cookieRefreshVisible.value = true;
    cookieRefreshSessionId.value = refresh.sessionId;
    cookieRefreshStatus.value = refresh.status;
    cookieRefreshMessage.value = refresh.message || '请使用闲鱼 APP 扫码并在手机上确认登录';
    cookieRefreshQrDataUrl.value = await QRCode.toDataURL(refresh.qrUrl, {
      margin: 1,
      width: 280,
    });
    startCookieRefreshPolling();
    void pollCookieRefreshStatus();
  } catch {
    message.error('生成 Cookie 二维码失败，请重试');
  } finally {
    cookieRefreshLoading.value = false;
    cookieRefreshQrLoading.value = false;
  }
}

function handleCloseCookieRefreshModal() {
  stopCookieRefreshPolling();
  cookieRefreshVisible.value = false;
  cookieRefreshSessionId.value = '';
  cookieRefreshQrDataUrl.value = '';
}

function cookieRefreshStatusColor(status: string) {
  if (status === 'success') return 'success';
  if (status === 'failed' || status === 'expired') return 'error';
  if (status === 'pending') return 'processing';
  return 'default';
}

// ==================== 初始化 ====================

onMounted(() => {
  void loadData();
  startTaskAutoRefresh();
});

onUnmounted(() => {
  stopCookieRefreshPolling();
  stopTaskAutoRefresh();
});
</script>

<template>
  <div>
    <!-- 搜索区 + 创建按钮 -->
    <div :style="{ background: '#f5f5f5', borderRadius: '8px', padding: '16px', marginBottom: '16px' }">
      <div style="display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 8px;">
        <a-form layout="inline" :model="searchForm">
          <a-form-item label="商品名称">
            <a-input
              v-model:value="searchForm.productName"
              placeholder="请输入商品名称"
              allow-clear
              style="width: 180px"
            />
          </a-form-item>
          <a-form-item label="任务状态">
            <a-select
              v-model:value="searchForm.status"
              placeholder="请选择状态"
              allow-clear
              :options="statusOptions"
              style="width: 120px"
            />
          </a-form-item>
          <a-form-item label="执行方式">
            <a-select
              v-model:value="searchForm.triggerMode"
              placeholder="请选择方式"
              allow-clear
              :options="triggerModeOptions"
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
        <a-space>
          <a-button :loading="cookieRefreshLoading" @click="handleRefreshCookie">
            刷新 Cookie
          </a-button>
          <a-button type="primary" @click="handleCreate">
            <PlusOutlined /> 创建任务
          </a-button>
        </a-space>
      </div>
    </div>

    <!-- 表格 -->
    <a-table
      :columns="columns"
      :data-source="dataSource"
      :loading="loading"
      row-key="id"
      :scroll="{ x: 1298 }"
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
        <!-- 价格范围 -->
        <template v-if="column.key === 'priceRange'">
          {{ formatPriceRange(record as SourceTaskRespVO) }}
        </template>

        <!-- 执行方式 -->
        <template v-if="column.key === 'triggerMode'">
          <a-tag v-if="(record as SourceTaskRespVO).triggerMode === 0" color="blue">一次性</a-tag>
          <span v-else-if="(record as SourceTaskRespVO).triggerMode === 1">
            <a-tag color="cyan">每日定时</a-tag>
            <span v-if="(record as SourceTaskRespVO).scheduleTime" style="margin-left: 4px; color: var(--color-text-secondary); font-size: 12px;">
              {{ (record as SourceTaskRespVO).scheduleTime }}
            </span>
          </span>
          <span v-else>-</span>
        </template>

        <!-- 任务状态 -->
        <template v-if="column.key === 'status'">
          <a-tag :color="statusColorMap[(record as SourceTaskRespVO).status] ?? 'default'">
            {{ statusLabelMap[(record as SourceTaskRespVO).status] ?? (record as SourceTaskRespVO).status }}
          </a-tag>
        </template>

        <!-- 创建时间 -->
        <template v-if="column.key === 'createTime'">
          {{ formatDateTime((record as SourceTaskRespVO).createTime) }}
        </template>

        <!-- 操作列：五个固定列，每个动作独占一格，删除始终位于最后一格 -->
        <template v-if="column.key === 'action'">
          <div class="source-task-actions">
            <div class="source-task-actions__slot">
              <a-button
                v-if="(record as SourceTaskRespVO).executedCount > 0"
                type="link"
                size="small"
                @click="handleViewResult(record as SourceTaskRespVO)"
              >
                查看结果
              </a-button>
            </div>
            <div class="source-task-actions__slot">
              <a-button
                v-if="(record as SourceTaskRespVO).executedCount > 0"
                type="link"
                size="small"
                :loading="downloadingId === (record as SourceTaskRespVO).id"
                @click="handleDownload(record as SourceTaskRespVO)"
              >
                下载文件
              </a-button>
            </div>
            <div class="source-task-actions__slot">
              <!-- 暂停/恢复：仅定时任务（triggerMode=1）显示 -->
              <a-button
                v-if="(record as SourceTaskRespVO).triggerMode === 1
                  && (record as SourceTaskRespVO).status === 0
                  && !(record as SourceTaskRespVO).latestExecutionEmpty"
                type="link"
                size="small"
                :loading="pausingId === (record as SourceTaskRespVO).id"
                @click="handlePause(record as SourceTaskRespVO)"
              >
                暂停
              </a-button>
              <a-button
                v-if="(record as SourceTaskRespVO).triggerMode === 1 && (record as SourceTaskRespVO).status === 1"
                type="link"
                size="small"
                :loading="resumingId === (record as SourceTaskRespVO).id"
                @click="handleResume(record as SourceTaskRespVO)"
              >
                恢复
              </a-button>
            </div>
            <div class="source-task-actions__slot">
              <!-- 重试：一次性失败 / 提交失败，或每日任务最近批次失败 -->
              <a-button
                v-if="(record as SourceTaskRespVO).retryable"
                type="link"
                size="small"
                :disabled="retryingTaskId !== null"
                :loading="retryingTaskId === (record as SourceTaskRespVO).id"
                @click="handleExecute(record as SourceTaskRespVO)"
              >
                重试
              </a-button>
            </div>
            <div class="source-task-actions__slot">
              <a-popconfirm
                title="永久删除此任务？"
                ok-text="删除"
                cancel-text="取消"
                @confirm="handleDelete(record as SourceTaskRespVO)"
              >
                <a-button
                  type="link"
                  danger
                  size="small"
                  :loading="deletingTaskId === (record as SourceTaskRespVO).id"
                >
                  删除
                </a-button>
              </a-popconfirm>
            </div>
          </div>
        </template>
      </template>
    </a-table>

    <!-- 创建任务弹窗 -->
    <CreateTaskModal ref="createTaskModalRef" @refresh="loadData" />

    <!-- 查看结果弹窗 -->
    <TaskResultModal ref="taskResultModalRef" />

    <a-modal
      :open="cookieRefreshVisible"
      title="刷新共享 Cookie"
      :footer="null"
      :mask-closable="false"
      @cancel="handleCloseCookieRefreshModal"
    >
      <div style="display: flex; flex-direction: column; align-items: center; gap: 12px;">
        <a-spin :spinning="cookieRefreshQrLoading">
          <div
            style="width: 280px; height: 280px; border: 1px solid #f0f0f0; border-radius: 8px; display: flex; align-items: center; justify-content: center; background: #fff;"
          >
            <img
              v-if="cookieRefreshQrDataUrl"
              :src="cookieRefreshQrDataUrl"
              alt="Cookie 二维码"
              style="width: 280px; height: 280px;"
            />
            <span v-else style="color: var(--color-text-secondary);">二维码生成中...</span>
          </div>
        </a-spin>
        <a-tag :color="cookieRefreshStatusColor(cookieRefreshStatus)">
          {{ cookieRefreshStatus }}
        </a-tag>
        <div style="text-align: center; color: var(--color-text-secondary); line-height: 1.6;">
          {{ cookieRefreshMessage }}
        </div>
        <div style="text-align: center; color: var(--color-text-muted); font-size: 12px;">
          使用闲鱼 APP 扫码并确认登录后，Cookie 会自动回写到共享池。
        </div>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.source-task-actions {
  display: grid;
  /* 查看结果 | 下载文件 | 暂停/恢复 | 重试 | 删除，每个动作独占一格 */
  grid-template-columns: 76px 76px 48px 48px 48px;
  column-gap: 0;
  align-items: center;
}

.source-task-actions__slot {
  min-width: 0;
}
</style>
