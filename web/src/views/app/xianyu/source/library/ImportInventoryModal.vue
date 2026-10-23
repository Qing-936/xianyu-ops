<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  Alert, Button, Checkbox, message, Modal, Space, Spin, Table, Tag, Tooltip, Upload,
} from 'ant-design-vue';
import { UploadOutlined } from '@ant-design/icons-vue';
import type {
  SourceInventoryImportParseItem,
  SourceInventoryImportResult,
} from '#/api/app/xianyu/source';
import {
  createSourceInventoryImport,
  parseSourceInventoryImport,
} from '#/api/app/xianyu/source';
import { fenToYuan } from '#/utils/format';

defineOptions({ name: 'ImportInventoryModal' });

const emit = defineEmits<{
  success: [];
}>();

const Dragger = Upload.Dragger;

/** 预览行：解析结果 + 勾选状态 */
interface ImportRow extends SourceInventoryImportParseItem {
  key: string;
  selected: boolean;
}

const visible = ref(false);
const step = ref<'upload' | 'preview' | 'result'>('upload');
const loading = ref(false);
const rows = ref<ImportRow[]>([]);
const results = ref<SourceInventoryImportResult[]>([]);

/** 预览表格列 */
const previewColumns = [
  { title: '', key: 'selected', width: 40, fixed: 'left' as const },
  { title: '行号', dataIndex: 'rowNo', width: 60 },
  { title: '标题', key: 'title', width: 260, ellipsis: true },
  { title: '平台', key: 'platform', width: 110 },
  { title: '价格(元)', key: 'price', width: 100 },
  { title: '图片数', key: 'images', width: 70 },
  { title: '卖家', key: 'seller', width: 120, ellipsis: true },
  { title: '状态', key: 'duplicated', width: 90 },
  { title: '警告', key: 'warnings', width: 220 },
];

/** 导入结果表格列 */
const resultColumns = [
  { title: '行号', dataIndex: 'rowNo', width: 70 },
  { title: '标题', dataIndex: 'title', ellipsis: true },
  { title: '结果', key: 'result', width: 170 },
  { title: '失败原因', key: 'message', width: 300, ellipsis: true },
];

const duplicateCount = computed(() => rows.value.filter((row) => row.duplicated).length);
const selectedCount = computed(() => rows.value.filter((row) => row.selected).length);

const open = (): void => {
  visible.value = true;
  step.value = 'upload';
  loading.value = false;
  rows.value = [];
  results.value = [];
};

const handleFile = async (file: File): Promise<boolean> => {
  loading.value = true;
  try {
    const parsed = await parseSourceInventoryImport(file);
    if (!parsed || parsed.length === 0) {
      message.warning('未解析到商品数据，请检查 Excel 内容');
      return false;
    }
    rows.value = parsed.map((item, index) => ({
      ...item,
      key: `import_${index}`,
      // 已入库的行默认不勾选
      selected: !item.duplicated,
    }));
    step.value = 'preview';
  } catch {
    message.error('Excel 解析失败，请检查文件格式');
  } finally {
    loading.value = false;
  }
  return false;
};

/** 全选可导入行（已入库或缺少链接的行跳过） */
const toggleSelectAll = (checked: boolean): void => {
  for (const row of rows.value) {
    row.selected = checked && !row.duplicated && !!row.link;
  }
};

const handleImportOk = async (): Promise<void> => {
  const selectedRows = rows.value.filter((row) => row.selected);
  if (selectedRows.length === 0) {
    message.warning('请勾选要导入的商品');
    return;
  }
  loading.value = true;
  try {
    const response = await createSourceInventoryImport({
      items: selectedRows.map((row) => ({
        rowNo: row.rowNo,
        title: row.title,
        link: row.link,
        externalItemId: row.externalItemId,
        price: row.price,
        images: row.images,
        seller: row.seller,
        itemCondition: row.itemCondition,
        functionStatus: row.functionStatus,
      })),
    });
    results.value = response;
    const successCount = response.filter((r) => r.success).length;
    message.success(`导入完成：成功 ${successCount} 条，失败 ${response.length - successCount} 条`);
    step.value = 'result';
    if (successCount > 0) {
      emit('success');
    }
  } catch {
    message.error('批量导入失败，请稍后重试');
  } finally {
    loading.value = false;
  }
};

const handleDone = (): void => {
  visible.value = false;
};

defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    title="批量导入货源"
    :width="1000"
    :footer="null"
    destroy-on-close
  >
    <!-- 步骤1：上传 Excel -->
    <div v-if="step === 'upload'">
      <Spin :spinning="loading">
        <Dragger
          :before-upload="handleFile"
          :show-upload-list="false"
          :disabled="loading"
          accept=".xls,.xlsx"
        >
          <p class="ant-upload-drag-icon"><UploadOutlined /></p>
          <p class="ant-upload-text">点击或拖拽 Excel 文件到此区域上传</p>
          <p class="ant-upload-hint">
            支持 .xls（XML 表格）/ .xlsx 格式；表头需包含「标题」「商品链接」等列；解析确认后直接写入货源库
          </p>
        </Dragger>
      </Spin>
    </div>

    <!-- 步骤2：预览与导入 -->
    <div v-else-if="step === 'preview'">
      <Alert
        type="info"
        show-icon
        class="summary-bar"
        :message="`共解析 ${rows.length} 条，已入库 ${duplicateCount} 条（默认跳过），已选 ${selectedCount} 条`"
      />
      <Table
        :columns="previewColumns"
        :data-source="rows"
        :pagination="false"
        :scroll="{ x: 1180, y: 360 }"
        row-key="key"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'selected'">
            <Checkbox
              :checked="record.selected"
              :disabled="record.duplicated || !record.link"
              @change="(e: any) => (record.selected = e.target.checked)"
            />
          </template>
          <template v-else-if="column.key === 'title'">
            <Tooltip :title="record.title">
              <span class="title-cell">{{ record.title }}</span>
            </Tooltip>
          </template>
          <template v-else-if="column.key === 'platform'">
            <Tag v-if="record.platform">{{ record.platform }}</Tag>
            <span v-else>-</span>
          </template>
          <template v-else-if="column.key === 'price'">
            <span v-if="record.price != null">¥{{ fenToYuan(record.price) }}</span>
            <Tag v-else color="red">价格缺失</Tag>
          </template>
          <template v-else-if="column.key === 'images'">
            <span>{{ Array.isArray(record.images) ? record.images.length : 0 }}</span>
          </template>
          <template v-else-if="column.key === 'seller'">
            <span>{{ record.seller || '-' }}</span>
          </template>
          <template v-else-if="column.key === 'duplicated'">
            <Tag v-if="record.duplicated" color="orange">已入库</Tag>
            <Tag v-else color="green">待导入</Tag>
          </template>
          <template v-else-if="column.key === 'warnings'">
            <Space v-if="record.warnings && record.warnings.length > 0" wrap>
              <Tag v-for="(w, i) in record.warnings" :key="i" color="orange">{{ w }}</Tag>
            </Space>
            <span v-else>-</span>
          </template>
        </template>
      </Table>
      <div class="footer-bar">
        <Space>
          <Button @click="toggleSelectAll(true)">全选可导入</Button>
          <Button @click="toggleSelectAll(false)">取消全选</Button>
          <Button @click="step = 'upload'">重新上传</Button>
          <Button type="primary" :loading="loading" @click="handleImportOk">确认导入</Button>
        </Space>
      </div>
    </div>

    <!-- 步骤3：导入结果 -->
    <div v-else>
      <Alert
        type="success"
        show-icon
        class="summary-bar"
        :message="`导入完成：成功 ${results.filter((r) => r.success).length} 条，失败 ${results.filter((r) => !r.success).length} 条`"
      />
      <Table
        :columns="resultColumns"
        :data-source="results"
        :pagination="false"
        size="small"
        row-key="rowNo"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'result'">
            <Tag :color="record.success ? 'green' : 'red'">{{ record.success ? '成功' : '失败' }}</Tag>
            <span v-if="record.success && record.inventoryId" class="result-id">货源ID: {{ record.inventoryId }}</span>
          </template>
          <template v-else-if="column.key === 'message'">
            <Tooltip v-if="record.message" :title="record.message">
              <span class="fail-message">{{ record.message }}</span>
            </Tooltip>
            <span v-else>-</span>
          </template>
        </template>
      </Table>
      <div class="footer-bar">
        <Button type="primary" @click="handleDone">完成</Button>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.summary-bar {
  margin-bottom: 12px;
}

.title-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-id {
  margin-left: 8px;
  color: var(--color-text-secondary);
}

.fail-message {
  color: var(--color-text-secondary);
}

.footer-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
