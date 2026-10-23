<script setup lang="ts">
import { computed, h, onActivated, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { Button, Card, DatePicker, Form, Image, Input, message, Modal, Popconfirm, Select, Space, Spin, Table, Tag } from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
import { DeleteOutlined, DownloadOutlined, LeftOutlined, PlusOutlined, ReloadOutlined, RightOutlined, SearchOutlined, UploadOutlined } from '@ant-design/icons-vue';
import dayjs, { type Dayjs } from 'dayjs';
import {
  batchDeleteSourceInventory,
  deleteSourceInventory,
  exportSourceInventoryCsv,
  getSourceInventoryPage,
  refreshSourceInventory,
  type SourceInventoryPageReqVO,
  type SourceInventoryRespVO,
} from '#/api/app/xianyu/source';
import { fenToYuan, formatDateTime } from '#/utils/format';
import ManualInventoryModal from './ManualInventoryModal.vue';
import ImportInventoryModal from './ImportInventoryModal.vue';
import CleanModal from './CleanModal.vue';
import RefineImageModal from './RefineImageModal.vue';

defineOptions({ name: 'AppXianyuSourceLibrary' });


const FormItem = Form.Item;
const RangePicker = DatePicker.RangePicker;

const inventoryStatusMap: Record<number, { label: string; color: string }> = {
  0: { label: '待审核', color: 'orange' },
  1: { label: '已入库', color: 'blue' },
  2: { label: '已创建商品', color: 'cyan' },
  3: { label: '已上架', color: 'green' },
  4: { label: '已售出', color: 'purple' },
  5: { label: '已下架', color: 'default' },
};

const cleanStatusMap: Record<number, { label: string; color: string }> = {
  1: { label: '清洗中', color: 'processing' },
  2: { label: '已清洗', color: 'success' },
  3: { label: '清洗失败', color: 'error' },
};

const loading = ref(false);
const dataSource = ref<SourceInventoryRespVO[]>([]);
const total = ref(0);
const refreshingId = ref<number | null>(null);
const pagination = reactive({ current: 1, pageSize: 20 });
const selectedRowKeys = ref<number[]>([]);
const manualInventoryModalRef = ref<InstanceType<typeof ManualInventoryModal> | null>(null);
const importInventoryModalRef = ref<InstanceType<typeof ImportInventoryModal> | null>(null);
const cleanModalRef = ref<InstanceType<typeof CleanModal> | null>(null);
const refineImageModalRef = ref<InstanceType<typeof RefineImageModal> | null>(null);
const previewVisible = ref(false);
const previewRecord = ref<SourceInventoryRespVO | null>(null);
const imagePreviewVisible = ref(false);
const imagePreviewImages = ref<string[]>([]);
const imagePreviewIndex = ref(0);
const searchForm = reactive<{
  searchKeyword?: string;
  title?: string;
  displayStatus?: number;
  inventoryTime?: [Dayjs, Dayjs];
}>({});

// 自动刷新逻辑（类似货源获取页面）
let listAutoRefreshTimer: number | null = null;
let activatedOnce = false;

const stopListAutoRefresh = () => {
  if (listAutoRefreshTimer !== null) {
    clearInterval(listAutoRefreshTimer);
    listAutoRefreshTimer = null;
  }
};

const hasCleaningItems = () => dataSource.value.some((item) => item.cleanStatus === 1);

const startListAutoRefresh = () => {
  stopListAutoRefresh();
  listAutoRefreshTimer = window.setInterval(() => {
    if (hasCleaningItems()) {
      void fetchData();
    } else {
      stopListAutoRefresh();
    }
  }, 10000);
};

const columns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '商品信息', dataIndex: 'title', width: 300 },
  { title: '价格(元)', dataIndex: 'price', width: 100 },
  { title: '品牌', dataIndex: 'brand', width: 100 },
  { title: '货源链接', dataIndex: 'detailUrl', width: 100 },
  { title: '状态', dataIndex: 'inventoryStatus', width: 180 },
  { title: '入库时间', dataIndex: 'inventoryTime', width: 180 },
  { title: '操作', dataIndex: 'action', width: 340, fixed: 'right' as const },
];

const displayOrDash = (value: unknown): string | number => {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'string') return value.trim() || '-';
  if (typeof value === 'number') return value;
  return String(value);
};

const displayTextOrDash = (value: unknown): string => String(displayOrDash(value));

const displayCellValue = (record: SourceInventoryRespVO, dataIndex: string) =>
  displayOrDash(record[dataIndex as keyof SourceInventoryRespVO]);

const resolveStatusDisplay = (record: SourceInventoryRespVO) => {
  if (record.cleanStatus === 3) return cleanStatusMap[3];
  if (record.cleanStatus === 1) return cleanStatusMap[1];
  if (record.xianyuProductId || record.inventoryStatus >= 2) {
    if (record.xianyuProductId || record.inventoryStatus === 2) {
      return { label: '已创建', color: inventoryStatusMap[2].color };
    }
    return inventoryStatusMap[record.inventoryStatus];
  }
  if (record.cleanStatus === 2) return cleanStatusMap[2];
  return inventoryStatusMap[record.inventoryStatus];
};

/** 图片微调拦截原因；返回空字符串表示可进入微调弹窗 */
const refineImageBlockReason = (record: SourceInventoryRespVO): string => {
  if (resolveStatusDisplay(record)?.label !== '已清洗') return '仅「已清洗」状态的货源可进行图片微调';
  if (!record.hasCleanHtml) return '无法进行图片微调，请重新清洗';
  return '';
};

const parseImageList = (images?: string): string[] => {
  if (!images) return [];
  try {
    const parsed: unknown = JSON.parse(images);
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string' && item.trim().length > 0) : [];
  } catch {
    return [];
  }
};

const resolvePreviewImages = (images?: string, fallbackImage?: string): string[] => {
  const imageList = parseImageList(images);
  return imageList.length > 0 ? imageList : fallbackImage ? [fallbackImage] : [];
};

const getInventoryPreviewImages = (record: SourceInventoryRespVO): string[] =>
  resolvePreviewImages(record.images, record.picUrl);

const previewImages = computed(() => {
  if (!previewRecord.value) return [];
  return resolvePreviewImages(previewRecord.value.images, previewRecord.value.picUrl);
});

const imagePreviewCurrent = computed(() => imagePreviewImages.value[imagePreviewIndex.value] || '');

const buildParams = (): SourceInventoryPageReqVO => ({
  pageNo: pagination.current,
  pageSize: pagination.pageSize,
  searchKeyword: searchForm.searchKeyword,
  title: searchForm.title,
  displayStatus: searchForm.displayStatus,
  inventoryTimeStart: searchForm.inventoryTime?.[0]?.startOf('day').format('YYYY-MM-DD[T]HH:mm:ss'),
  inventoryTimeEnd: searchForm.inventoryTime?.[1]?.endOf('day').format('YYYY-MM-DD[T]HH:mm:ss'),
});

const fetchData = async () => {
  loading.value = true;
  try {
    const result = await getSourceInventoryPage(buildParams());
    dataSource.value = result.list ?? [];
    total.value = result.total ?? 0;

    // 检查是否有清洗中的项，决定是否开启自动刷新
    if (hasCleaningItems()) {
      startListAutoRefresh();
    } else {
      stopListAutoRefresh();
    }
  } finally {
    loading.value = false;
  }
};

onBeforeUnmount(() => {
  stopListAutoRefresh();
});

const handleSearch = () => {
  pagination.current = 1;
  void fetchData();
};

const handleReset = () => {
  Object.assign(searchForm, { searchKeyword: undefined, title: undefined, displayStatus: undefined, inventoryTime: undefined });
  handleSearch();
};

const handleTableChange: NonNullable<TableProps['onChange']> = (page) => {
  pagination.current = page.current ?? 1;
  pagination.pageSize = page.pageSize ?? pagination.pageSize;
  void fetchData();
};

const handleDelete = async (id: number) => {
  await deleteSourceInventory(id);
  message.success('删除成功');
  await fetchData();
};

const handleRefresh = async (record: SourceInventoryRespVO): Promise<void> => {
  if (record.xianyuProductId || record.inventoryStatus === 2) {
    message.warning('已创建的商品无法刷新');
    return;
  }
  refreshingId.value = record.id;
  try {
    await refreshSourceInventory(record.id);
    message.success('刷新成功');
    await fetchData();
  } finally {
    refreshingId.value = null;
  }
};

const handleManualInventory = (): void => {
  manualInventoryModalRef.value?.open();
};

const handleImportInventory = (): void => {
  importInventoryModalRef.value?.open();
};

const handleCleanModalOpen = (): void => {
  if (!selectedRowKeys.value.length) {
    message.warning('请先选择要清洗的货源');
    return;
  }
  cleanModalRef.value?.open(selectedRowKeys.value);
};

const handleCleanSuccess = (): void => {
  selectedRowKeys.value = [];
  void fetchData();
};

const handleOpenInventoryPreview = (record: SourceInventoryRespVO): void => {
  previewRecord.value = record;
  previewVisible.value = true;
};

const handleOpenImagePreview = (record: SourceInventoryRespVO): void => {
  const images = getInventoryPreviewImages(record);
  if (!images.length) return;
  imagePreviewImages.value = images;
  imagePreviewIndex.value = 0;
  imagePreviewVisible.value = true;
};

const handleSelectPreviewImage = (index: number): void => {
  imagePreviewIndex.value = index;
};

const handlePreviousPreviewImage = (): void => {
  if (imagePreviewImages.value.length < 2) return;
  imagePreviewIndex.value =
    (imagePreviewIndex.value - 1 + imagePreviewImages.value.length) % imagePreviewImages.value.length;
};

const handleNextPreviewImage = (): void => {
  if (imagePreviewImages.value.length < 2) return;
  imagePreviewIndex.value = (imagePreviewIndex.value + 1) % imagePreviewImages.value.length;
};

const handleRefineImage = (record: SourceInventoryRespVO): void => {
  const reason = refineImageBlockReason(record);
  if (reason) {
    message.warning(reason);
    return;
  }
  refineImageModalRef.value?.open(record);
};

const handleBatchDelete = async () => {
  if (!selectedRowKeys.value.length) {
    message.warning('请先选择货源');
    return;
  }
  await batchDeleteSourceInventory(selectedRowKeys.value);
  message.success('删除成功');
  selectedRowKeys.value = [];
  await fetchData();
};

const handleExport = async () => {
  const { pageNo: _pageNo, pageSize: _pageSize, ...params } = buildParams();
  try {
    const response = await exportSourceInventoryCsv(params);
    const blob = response.data;
    if (blob.type.includes('application/json')) {
      const result: { msg?: string } = JSON.parse(await blob.text());
      message.error(result.msg || '导出失败，请稍后重试');
      return;
    }
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = '货源库.csv';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  } catch {
    message.error('导出失败，请稍后重试');
  }
};

onMounted(() => void fetchData());
onActivated(() => {
  if (activatedOnce) void fetchData();
  activatedOnce = true;
});
</script>

<template>
  <Card :bordered="false">
    <Form layout="inline" :model="searchForm" class="search-form">
      <FormItem label="关键词">
        <Input
          v-model:value="searchForm.searchKeyword"
          placeholder="搜索关键词"
          style="width: 160px"
          allow-clear
          @press-enter="handleSearch"
        />
      </FormItem>
      <FormItem label="商品名称">
        <Input
          v-model:value="searchForm.title"
          placeholder="商品名称"
          style="width: 180px"
          allow-clear
          @press-enter="handleSearch"
        />
      </FormItem>
      <FormItem label="状态">
        <Select
          v-model:value="searchForm.displayStatus"
          placeholder="状态"
          style="width: 120px"
          allow-clear
        >
          <Select.Option :value="1">已入库</Select.Option>
          <Select.Option :value="2">已创建</Select.Option>
          <Select.Option :value="6">清洗中</Select.Option>
          <Select.Option :value="7">已清洗</Select.Option>
          <Select.Option :value="8">清洗失败</Select.Option>
        </Select>
      </FormItem>
      <FormItem label="入库时间">
        <RangePicker
          v-model:value="searchForm.inventoryTime"
          :show-time="false"
          format="YYYY-MM-DD"
          style="width: 240px"
        />
      </FormItem>
      <FormItem>
        <Space>
          <Button type="primary" :icon="h(SearchOutlined)" @click="handleSearch">搜索</Button>
          <Button :icon="h(ReloadOutlined)" @click="handleReset">重置</Button>
        </Space>
      </FormItem>
    </Form>

    <div class="toolbar">
      <Space>
        <Button type="primary" :icon="h(PlusOutlined)" @click="handleManualInventory">人工入库</Button>
        <Button :icon="h(UploadOutlined)" @click="handleImportInventory">批量导入</Button>
        <Button :icon="h(DownloadOutlined)" @click="handleExport">
          导出<span v-if="selectedRowKeys.length">({{ selectedRowKeys.length }}条)</span>
        </Button>
        <Button
          type="primary"
          :disabled="selectedRowKeys.length === 0"
          @click="handleCleanModalOpen"
        >
          AI清洗<span v-if="selectedRowKeys.length">({{ selectedRowKeys.length }}条)</span>
        </Button>
        <Popconfirm
          title="确认删除选中的货源？"
          ok-text="确认"
          cancel-text="取消"
          @confirm="handleBatchDelete"
        >
          <Button
            danger
            :icon="h(DeleteOutlined)"
            :disabled="selectedRowKeys.length === 0"
          >
            批量删除
          </Button>
        </Popconfirm>
      </Space>
    </div>

    <Table
      :columns="columns"
      :data-source="dataSource"
      :loading="loading"
      :pagination="{
        current: pagination.current,
        pageSize: pagination.pageSize,
        total: total,
        showSizeChanger: true,
        showQuickJumper: true,
        showTotal: (total: number) => `共 ${total} 条`,
      }"
      :row-selection="{
        selectedRowKeys: selectedRowKeys,
        onChange: (keys: Array<string | number>) => (selectedRowKeys = keys.map(Number)),
      }"
      row-key="id"
      :scroll="{ x: 1370, y: 'calc(100vh - 360px)' }"
      :sticky="{ offsetHeader: 64 }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'title'">
          <div class="product-cell">
            <div
              v-if="getInventoryPreviewImages(record as SourceInventoryRespVO).length > 0"
              class="product-image-trigger"
              @click="handleOpenImagePreview(record as SourceInventoryRespVO)"
            >
              <Image
                :src="getInventoryPreviewImages(record as SourceInventoryRespVO)[0]"
                :width="60"
                :height="60"
                :preview="false"
                alt="商品主图"
                class="product-image"
              />
            </div>
            <div v-else class="product-image product-image-placeholder">-</div>
            <div class="product-copy">
              <a
                class="product-title"
                :title="displayTextOrDash(record.title)"
                @click.prevent="handleOpenInventoryPreview(record as SourceInventoryRespVO)"
              >
                {{ displayOrDash(record.title) }}
              </a>
              <div class="product-desc" :title="displayTextOrDash(record.description)">
                {{ displayOrDash(record.description) }}
              </div>
            </div>
          </div>
        </template>

        <template v-else-if="column.dataIndex === 'price'">
          <div class="price-cell">
            {{ record.price == null ? '-' : fenToYuan(record.price) }}
          </div>
        </template>

        <template v-else-if="column.dataIndex === 'detailUrl'">
          <a
            v-if="record.detailUrl"
            :href="record.detailUrl"
            target="_blank"
            rel="noopener noreferrer"
            style="color: var(--color-primary)"
          >
            查看
          </a>
          <span v-else>-</span>
        </template>

        <template v-else-if="column.dataIndex === 'inventoryStatus'">
          <Tag :color="resolveStatusDisplay(record as SourceInventoryRespVO)?.color || 'default'">
            {{ resolveStatusDisplay(record as SourceInventoryRespVO)?.label || '未知' }}
          </Tag>
        </template>

        <template v-else-if="column.dataIndex === 'inventoryTime'">
          {{ record.inventoryTime ? formatDateTime(record.inventoryTime) : '-' }}
        </template>

        <template v-else-if="column.dataIndex === 'action'">
          <Space>
            <Button
              type="link"
              size="small"
              :loading="refreshingId === record.id"
              :disabled="refreshingId !== null && refreshingId !== record.id"
              @click="handleRefresh(record as SourceInventoryRespVO)"
            >
              刷新
            </Button>
            <Popconfirm
              title="确认删除？"
              ok-text="确认"
              cancel-text="取消"
              @confirm="handleDelete(record.id)"
            >
              <Button type="link" danger size="small">删除</Button>
            </Popconfirm>
            <Button
              type="link"
              size="small"
              @click="handleRefineImage(record as SourceInventoryRespVO)"
            >
              图片微调
            </Button>
          </Space>
        </template>

        <template v-else>
          {{ displayCellValue(record as SourceInventoryRespVO, String(column.dataIndex)) }}
        </template>
      </template>
    </Table>

    <ManualInventoryModal ref="manualInventoryModalRef" @success="fetchData" />
    <ImportInventoryModal ref="importInventoryModalRef" @success="fetchData" />
    <CleanModal ref="cleanModalRef" @success="handleCleanSuccess" />
    <RefineImageModal ref="refineImageModalRef" @success="fetchData" />
    <Modal
      v-model:open="imagePreviewVisible"
      :footer="null"
      :width="1200"
      centered
      class="image-preview-modal"
      wrap-class-name="image-preview-wrap"
    >
      <div class="image-preview-viewer">
        <Button
          class="image-preview-arrow image-preview-arrow-left"
          type="text"
          aria-label="上一张"
          @click="handlePreviousPreviewImage"
        >
          <LeftOutlined />
        </Button>
        <div class="image-preview-main">
          <img v-if="imagePreviewCurrent" :src="imagePreviewCurrent" alt="商品图片预览" />
          <span class="image-preview-counter">
            {{ imagePreviewIndex + 1 }} / {{ imagePreviewImages.length }}
          </span>
        </div>
        <Button
          class="image-preview-arrow image-preview-arrow-right"
          type="text"
          aria-label="下一张"
          @click="handleNextPreviewImage"
        >
          <RightOutlined />
        </Button>
        <div class="image-preview-thumbnails">
          <button
            v-for="(image, index) in imagePreviewImages"
            :key="image + index"
            type="button"
            :class="['image-preview-thumbnail', { 'image-preview-thumbnail-active': index === imagePreviewIndex }]"
            :aria-label="`查看第 ${index + 1} 张图片`"
            @click="handleSelectPreviewImage(index)"
          >
            <img :src="image" alt="" />
          </button>
        </div>
      </div>
    </Modal>
    <Modal
      v-model:open="previewVisible"
      title="货源内容"
      :footer="null"
      :width="720"
    >
      <div v-if="previewRecord" class="preview-content">
          <div class="preview-block">
            <div class="preview-label">标题</div>
            <div class="preview-title">{{ previewRecord.title || '-' }}</div>
          </div>
          <div class="preview-block">
            <div class="preview-label">描述</div>
            <div class="preview-description">{{ previewRecord.description || '-' }}</div>
          </div>
          <div class="preview-block">
            <div class="preview-label">图片（{{ previewImages.length }}）</div>
            <div class="preview-images">
              <Image.PreviewGroup>
                <Image
                  v-for="(image, index) in previewImages"
                  :key="index"
                  :src="image"
                  :width="80"
                  :height="80"
                  class="preview-image"
                />
              </Image.PreviewGroup>
              <span v-if="!previewImages.length" class="preview-empty">-</span>
            </div>
          </div>
        </div>
    </Modal>
  </Card>
</template>

<style scoped>
.search-form {
  margin-bottom: 16px;
}

.toolbar {
  margin-bottom: 16px;
}

.product-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.product-image {
  display: inline-block;
  border-radius: 4px;
  overflow: hidden;
  cursor: zoom-in;
}

.product-image-trigger {
  cursor: zoom-in;
}

.product-image :deep(.ant-image-img) {
  object-fit: cover;
}

.product-image-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  height: 60px;
  color: var(--color-text-muted);
  background: var(--color-bg);
  border-radius: 4px;
}

.product-copy {
  flex: 1;
  min-width: 0;
}

.product-title {
  display: block;
  margin-bottom: 4px;
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-primary);
  cursor: pointer;

  &:hover {
    color: var(--color-primary);
  }
}

.product-desc {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-muted);
}

.price-cell {
  font-weight: 500;
}

:deep(.ant-table-cell) {
  vertical-align: middle;
}

.preview-content {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.preview-label {
  margin-bottom: 6px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.preview-title {
  color: var(--color-text-primary);
  font-weight: 500;
  word-break: break-word;
}

.preview-description {
  color: var(--color-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.preview-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preview-image {
  object-fit: cover;
  border-radius: 4px;
}

.preview-empty {
  color: var(--color-text-muted);
}

.image-preview-viewer {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: min(780px, calc(100vh - 96px));
  padding: 28px 72px 20px;
  background: rgb(244 247 250 / 78%);
  backdrop-filter: blur(24px) saturate(120%);
}

.image-preview-main {
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-height: min(640px, calc(100vh - 220px));
  padding: 8px 0 24px;
}

.image-preview-main img {
  display: block;
  max-width: 100%;
  max-height: min(650px, calc(100vh - 250px));
  object-fit: contain;
}

.image-preview-counter {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  color: var(--color-text-muted);
  font-size: 12px;
  text-align: center;
}

.image-preview-arrow {
  position: absolute;
  z-index: 1;
  top: 46%;
  width: 42px;
  height: 42px;
  padding: 0;
  border-radius: 50%;
  color: var(--color-text-secondary);
  font-size: 16px;
  background: rgb(245 245 247 / 76%);
  backdrop-filter: blur(8px);
}

.image-preview-arrow:hover,
.image-preview-arrow:focus {
  color: var(--color-text-primary);
  background: rgb(232 232 237 / 92%);
}

.image-preview-arrow-left {
  left: 10px;
}

.image-preview-arrow-right {
  right: 10px;
}

.image-preview-thumbnails {
  align-self: center;
  display: flex;
  gap: 8px;
  max-width: min(100%, 900px);
  padding: 12px 14px;
  overflow-x: auto;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: rgb(245 245 247 / 78%);
  backdrop-filter: blur(12px);
}

.image-preview-thumbnail {
  flex: 0 0 72px;
  width: 72px;
  height: 72px;
  padding: 2px;
  overflow: hidden;
  border: 2px solid transparent;
  border-radius: 6px;
  background: rgb(255 255 255 / 82%);
  cursor: pointer;
  opacity: 0.78;
  transition: border-color 0.2s, opacity 0.2s;
}

.image-preview-thumbnail-active {
  border-color: #7bdc45;
  opacity: 1;
}

.image-preview-thumbnail img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

:deep(.image-preview-modal .ant-modal-content) {
  padding: 0;
  overflow: hidden;
  border-radius: 10px;
  border: 1px solid rgb(255 255 255 / 86%);
  background: rgb(244 247 250 / 78%);
  box-shadow: 0 18px 52px rgb(26 26 46 / 24%), 0 0 0 1px rgb(26 26 46 / 6%);
  backdrop-filter: blur(24px) saturate(120%);
}

:deep(.image-preview-wrap .ant-modal-mask) {
  background: rgb(26 26 46 / 24%);
  backdrop-filter: blur(8px);
}

:deep(.image-preview-modal .ant-modal-body) {
  padding: 0;
}

:deep(.image-preview-modal .ant-modal-close) {
  z-index: 2;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: var(--color-text-secondary);
  background: rgb(245 245 247 / 78%);
  backdrop-filter: blur(8px);
}

:deep(.image-preview-modal .ant-modal-close:hover) {
  color: var(--color-text-primary);
  background: rgb(232 232 237 / 92%);
}

@media (max-width: 768px) {
  :deep(.image-preview-modal) {
    max-width: calc(100vw - 24px);
  }

  .image-preview-viewer {
    min-height: calc(100vh - 120px);
    padding: 20px 44px 16px;
  }

  .image-preview-main {
    min-height: calc(100vh - 280px);
  }

  .image-preview-main img {
    max-height: calc(100vh - 330px);
  }
}
</style>
