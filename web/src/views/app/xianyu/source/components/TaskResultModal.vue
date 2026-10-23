<script setup lang="ts">
import { computed, ref } from 'vue';
import { message } from 'ant-design-vue';
import {
  addSourceItemToInventory,
  getSourceExecutionList,
  getSourceItemList,
  markSourceTaskViewed,
  type SourceExecutionRespVO,
  type SourceItemRespVO,
} from '#/api/app/xianyu/source';
import { fenToYuan, formatDateTime } from '#/utils/format';
import BlockSellerModal from './BlockSellerModal.vue';

defineOptions({ name: 'TaskResultModal' });
const emit = defineEmits<{ inventorySuccess: []; closed: [] }>();

// ==================== 状态 ====================

const visible = ref(false);
const loadingExecutions = ref(false);
const loadingItems = ref(false);
const currentTaskId = ref<number>(0);

const executions = ref<SourceExecutionRespVO[]>([]);
const selectedExecution = ref<SourceExecutionRespVO | null>(null);
const items = ref<SourceItemRespVO[]>([]);
const inventoryingIds = ref<Set<number>>(new Set());
const blockSellerModalRef = ref<InstanceType<typeof BlockSellerModal>>();

// ==================== 批量入库 ====================

const batchInventorying = ref(false);
const batchProgress = ref({ total: 0, done: 0, success: 0, failed: 0 });
const selectedRowKeys = ref<number[]>([]);

/** 是否可批量入库：有详情记录且尚未入库 */
function canBatchInventory(item: SourceItemRespVO): boolean {
  return item.detailId != null && item.sourceInventoryId == null;
}

const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys as number[];
  },
  getCheckboxProps: (record: SourceItemRespVO) => ({
    disabled: !canBatchInventory(record),
  }),
}));

// ==================== 表格列定义 ====================

const columns = [
  { title: '货源ID', key: 'sourceInventoryId', width: 90 },
  { title: '关键词', key: 'searchKeyword', ellipsis: true, width: 140 },
  { title: '商品名称', key: 'title', ellipsis: true, width: 180 },
  { title: '卖家', key: 'sellerName', width: 100 },
  { title: '价格', key: 'price', width: 90 },
  { title: '获取日期', key: 'createTime', width: 170 },
  { title: '商品链接', key: 'detailUrl', width: 90 },
  { title: '浏览量', key: 'viewCount', width: 80, align: 'right' as const },
  { title: '想要量', key: 'wantCount', width: 80, align: 'right' as const },
  { title: '热度', key: 'heatLevel', width: 70, align: 'center' as const },
  { title: '高价值', key: 'highValue', width: 80, align: 'center' as const },
  { title: '竞争力', key: 'competitiveness', width: 80, align: 'center' as const },
  { title: '卖家级别', key: 'sellerLevel', width: 90, align: 'center' as const },
  { title: '操作', key: 'action', width: 150, fixed: 'right' as const },
];

// ==================== 数据加载 ====================

async function loadExecutions(taskId: number, preferredExecutionNo?: number) {
  loadingExecutions.value = true;
  try {
    const list = await getSourceExecutionList(taskId);
    // 按 executionNo 降序排列（最新批次在前）
    executions.value = (list || []).sort(
      (a, b) => b.executionNo - a.executionNo,
    );
    // 默认选中指定批次，否则选最新批次。
    if (executions.value.length > 0) {
      selectedExecution.value = executions.value.find(
        (execution) => execution.executionNo === preferredExecutionNo,
      ) ?? executions.value[0]!;
      // 标记任务已查看（失败静默，不影响查看结果）
      await markSourceTaskViewed(taskId, selectedExecution.value.executionNo).catch(() => {});
      await loadItems();
    } else {
      selectedExecution.value = null;
      items.value = [];
    }
  } catch {
    message.error('加载批次列表失败');
    executions.value = [];
    selectedExecution.value = null;
    items.value = [];
  } finally {
    loadingExecutions.value = false;
  }
}

async function loadItems() {
  if (!selectedExecution.value) return;
  loadingItems.value = true;
  try {
    const list = await getSourceItemList(
      currentTaskId.value,
      selectedExecution.value.executionNo,
    );
    items.value = list || [];
    selectedRowKeys.value = [];
  } catch {
    message.error('加载搜索结果失败');
    items.value = [];
    selectedRowKeys.value = [];
  } finally {
    loadingItems.value = false;
  }
}

// ==================== 批次切换 ====================

async function handleSelectExecution(exec: SourceExecutionRespVO) {
  if (selectedExecution.value?.executionNo === exec.executionNo) return;
  selectedExecution.value = exec;
  await loadItems();
}

// ==================== 价格展示工具 ====================

function formatPrice(price: number | null | undefined): string {
  if (price === null || price === undefined) return '-';
  return `¥${fenToYuan(price)}`;
}

// ==================== 等级颜色映射 ====================

function getLevelColor(level: string | undefined | null): string {
  if (!level) return 'default';
  const colorMap: Record<string, string> = {
    '高': 'red',
    '中': 'orange',
    '低': 'green',
    '-': 'default',
  };
  return colorMap[level] || 'default';
}

// ==================== 操作 ====================

function isInventorying(item: SourceItemRespVO) {
  return item.detailId !== undefined && inventoryingIds.value.has(item.detailId);
}

async function handleAddToInventory(item: SourceItemRespVO) {
  if (!item.detailId) {
    message.error('该结果缺少详情记录，暂无法入库');
    return;
  }
  if (inventoryingIds.value.has(item.detailId)) return;
  inventoryingIds.value.add(item.detailId);
  try {
    await addSourceItemToInventory(item.detailId);
    message.success(item.sourceInventoryId ? '已覆盖更新' : '已入库');
    emit('inventorySuccess');
    await loadItems();
  } catch {
    // 失败提示统一由 request 全局拦截器弹出，此处不重复提示
  } finally {
    inventoryingIds.value.delete(item.detailId);
  }
}

function handleBlockedSeller(item: SourceItemRespVO) {
  blockSellerModalRef.value?.open({
    sellerName: item.sellerName,
    detailId: item.detailId,
    sourceTaskId: currentTaskId.value,
  });
}

async function handleBatchInventory() {
  if (batchInventorying.value) return;
  // 仅处理勾选且可入库（有 detailId 且未入库）的商品
  const targets = items.value.filter(
    (item) => selectedRowKeys.value.includes(item.id) && canBatchInventory(item),
  );
  if (targets.length === 0) {
    message.info('请先勾选要入库的商品');
    return;
  }
  batchInventorying.value = true;
  batchProgress.value = { total: targets.length, done: 0, success: 0, failed: 0 };
  try {
    for (const item of targets) {
      try {
        await addSourceItemToInventory(item.detailId!);
        batchProgress.value.success += 1;
      } catch {
        // 失败提示由 request 全局拦截器弹出，此处仅计数
        batchProgress.value.failed += 1;
      }
      batchProgress.value.done += 1;
    }
    const { success, failed } = batchProgress.value;
    if (failed === 0) {
      message.success(`批量入库完成：成功 ${success} 条`);
    } else {
      message.warning(`批量入库完成：成功 ${success} 条，失败 ${failed} 条`);
    }
    if (success > 0) {
      emit('inventorySuccess');
    }
    selectedRowKeys.value = [];
    await loadItems();
  } finally {
    batchInventorying.value = false;
  }
}

// ==================== 对外暴露 ====================

function open(taskId: number, preferredExecutionNo?: number) {
  currentTaskId.value = taskId;
  executions.value = [];
  selectedExecution.value = null;
  items.value = [];
  selectedRowKeys.value = [];
  visible.value = true;
  loadExecutions(taskId, preferredExecutionNo);
}

defineExpose({ open });
</script>

<template>
  <a-modal
    v-model:open="visible"
    title="查看采集结果"
    :width="1200"
    :footer="null"
    destroy-on-close
    :after-close="() => emit('closed')"
  >
    <a-spin :spinning="loadingExecutions">
      <!-- 无批次数据 -->
      <template v-if="!loadingExecutions && executions.length === 0">
        <a-empty description="暂无结果数据" style="padding: 40px 0" />
      </template>

      <template v-else-if="executions.length > 0">
        <!-- 批次选择器 -->
        <div style="margin-bottom: 16px">
          <div style="font-weight: 500; margin-bottom: 8px; color: var(--color-text-primary)">
            执行批次
          </div>
          <a-space wrap>
            <a-tag
              v-for="exec in executions"
              :key="exec.executionNo"
              :color="selectedExecution?.executionNo === exec.executionNo ? 'blue' : 'default'"
              style="cursor: pointer; margin-bottom: 4px"
              @click="handleSelectExecution(exec)"
            >
              批次 #{{ exec.executionNo }}
            </a-tag>
          </a-space>
        </div>

        <!-- Summary 卡片 -->
        <div
          v-if="selectedExecution"
          style="background: #fafafa; border: 1px solid #f0f0f0; border-radius: 8px; padding: 16px; margin-bottom: 16px"
        >
          <a-descriptions :column="{ xs: 1, sm: 2, md: 3 }" size="small">
            <a-descriptions-item label="搜索关键词">
              {{ selectedExecution.keyword || '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="获取数量">
              {{ selectedExecution.fetchCount }}
            </a-descriptions-item>
            <a-descriptions-item label="有效数">
              <a-tag color="success">{{ selectedExecution.validCount }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="最低价">
              {{ formatPrice(selectedExecution.minPrice) }}
            </a-descriptions-item>
            <a-descriptions-item label="最高价">
              {{ formatPrice(selectedExecution.maxPrice) }}
            </a-descriptions-item>
            <a-descriptions-item label="平均价">
              {{ formatPrice(selectedExecution.avgPrice) }}
            </a-descriptions-item>
            <a-descriptions-item label="价格战指数">
              {{ selectedExecution.priceIndex ?? '-' }}
            </a-descriptions-item>
          </a-descriptions>
        </div>

        <!-- 批量入库工具条 -->
        <div
          v-if="selectedExecution"
          style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px;"
        >
          <a-button
            type="primary"
            size="small"
            :loading="batchInventorying"
            :disabled="batchInventorying"
            @click="handleBatchInventory"
          >
            批量入库{{ selectedRowKeys.length ? `（${selectedRowKeys.length}）` : '' }}
          </a-button>
          <template v-if="batchInventorying">
            <a-progress
              :percent="
                batchProgress.total > 0
                  ? Math.round((batchProgress.done / batchProgress.total) * 100)
                  : 0
              "
              :show-info="false"
              :stroke-width="6"
              style="flex: 1; margin: 0;"
            />
            <span
              style="white-space: nowrap; color: var(--color-text-secondary); font-size: 12px;"
            >
              {{ batchProgress.done }}/{{ batchProgress.total }} · 成功
              {{ batchProgress.success }} · 失败 {{ batchProgress.failed }}
            </span>
          </template>
        </div>

        <!-- 结果表格 -->
        <a-table
          :columns="columns"
          :data-source="items"
          :loading="loadingItems"
          :row-selection="rowSelection"
          row-key="id"
          size="small"
          :pagination="{ pageSize: 10, showTotal: (total: number) => `共 ${total} 条` }"
          :scroll="{ x: 1490 }"
        >
          <template #bodyCell="{ column, record }">
            <!-- 货源ID（未入库显示 -） -->
            <template v-if="column.key === 'sourceInventoryId'">
              {{ (record as SourceItemRespVO).sourceInventoryId ?? '-' }}
            </template>
            <!-- 关键词 -->
            <template v-if="column.key === 'searchKeyword'">
              {{ (record as SourceItemRespVO).searchKeyword || '-' }}
            </template>
            <!-- 商品名称 -->
            <template v-if="column.key === 'title'">
              {{ (record as SourceItemRespVO).title || '-' }}
            </template>
            <!-- 卖家 -->
            <template v-if="column.key === 'sellerName'">
              {{ (record as SourceItemRespVO).sellerName || '-' }}
            </template>
            <!-- 价格 -->
            <template v-if="column.key === 'price'">
              ¥{{ fenToYuan((record as SourceItemRespVO).price) }}
            </template>
            <!-- 获取日期 -->
            <template v-if="column.key === 'createTime'">
              {{ formatDateTime((record as SourceItemRespVO).createTime) }}
            </template>
            <!-- 商品链接 -->
            <template v-if="column.key === 'detailUrl'">
              <a v-if="(record as SourceItemRespVO).detailUrl" :href="(record as SourceItemRespVO).detailUrl" target="_blank" rel="noopener noreferrer">查看</a>
              <span v-else>-</span>
            </template>
            <!-- 浏览量 -->
            <template v-if="column.key === 'viewCount'">
              {{ (record as SourceItemRespVO).viewCount ?? '-' }}
            </template>
            <!-- 想要量 -->
            <template v-if="column.key === 'wantCount'">
              {{ (record as SourceItemRespVO).wantCount ?? '-' }}
            </template>
            <!-- 热度 -->
            <template v-if="column.key === 'heatLevel'">
              <a-tag :color="getLevelColor((record as SourceItemRespVO).heatLevel)">
                {{ (record as SourceItemRespVO).heatLevel || '-' }}
              </a-tag>
            </template>
            <!-- 高价值 -->
            <template v-if="column.key === 'highValue'">
              <a-tag :color="getLevelColor((record as SourceItemRespVO).highValue)">
                {{ (record as SourceItemRespVO).highValue || '-' }}
              </a-tag>
            </template>
            <!-- 竞争力 -->
            <template v-if="column.key === 'competitiveness'">
              <a-tag :color="getLevelColor((record as SourceItemRespVO).competitiveness)">
                {{ (record as SourceItemRespVO).competitiveness || '-' }}
              </a-tag>
            </template>
            <!-- 卖家级别 -->
            <template v-if="column.key === 'sellerLevel'">
              <a-tag :color="(record as SourceItemRespVO).sellerLevel === '优质' ? 'blue' : 'default'">
                {{ (record as SourceItemRespVO).sellerLevel || '-' }}
              </a-tag>
            </template>
            <!-- 操作 -->
            <template v-if="column.key === 'action'">
              <a-space size="small">
                <a-button
                  type="link"
                  size="small"
                  :loading="isInventorying(record as SourceItemRespVO)"
                  :disabled="
                    !(record as SourceItemRespVO).detailId
                    || isInventorying(record as SourceItemRespVO)
                  "
                  @click="handleAddToInventory(record as SourceItemRespVO)"
                >
                  {{ (record as SourceItemRespVO).sourceInventoryId ? '覆盖' : '入库' }}
                </a-button>
                <!-- 拉黑卖家：弹窗选择/填写拉黑原因 -->
                <a-button
                  type="link"
                  size="small"
                  danger
                  @click="handleBlockedSeller(record as SourceItemRespVO)"
                >
                  拉黑卖家
                </a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </template>
    </a-spin>
  </a-modal>

  <!-- 拉黑卖家弹窗 -->
  <BlockSellerModal ref="blockSellerModalRef" @success="loadItems" />
</template>
