<script setup lang="ts">
import { h } from 'vue';
import { message, Modal } from 'ant-design-vue';
import type { SourceInventoryManualImportRespVO } from '#/api/app/xianyu/source';
import { submitSourceInventoryManualImport } from '#/api/app/xianyu/source';

defineOptions({ name: 'ManualInventoryModal' });

const emit = defineEmits<{
  success: [];
}>();

const visible = ref(false);
const submitting = ref(false);
const detailUrlsText = ref('');

const isXianyuUrl = (value: string): boolean => {
  try {
    const { hostname } = new URL(value);
    return hostname === 'goofish.com' || hostname.endsWith('.goofish.com')
      || hostname === '2.taobao.com' || hostname.endsWith('.taobao.com');
  } catch {
    return false;
  }
};

const open = (): void => {
  detailUrlsText.value = '';
  visible.value = true;
};

const parseDetailUrls = (): { urls: string[]; invalidUrls: string[] } => {
  const urls: string[] = [];
  const invalidUrls: string[] = [];
  const seen = new Set<string>();
  for (const line of detailUrlsText.value.split(/\r?\n/g)) {
    const normalizedUrl = line.trim();
    if (!normalizedUrl || seen.has(normalizedUrl)) {
      continue;
    }
    seen.add(normalizedUrl);
    if (!isXianyuUrl(normalizedUrl)) {
      invalidUrls.push(normalizedUrl);
      continue;
    }
    urls.push(normalizedUrl);
  }
  return { urls, invalidUrls };
};

const showImportSummary = (result: SourceInventoryManualImportRespVO): void => {
  const failedResults = result.results.filter((item) => !item.success);
  Modal.info({
    title: result.failedCount > 0 ? '批量入库完成（含失败项）' : '批量入库完成',
    width: 680,
    content: h('div', [
      h('p', `共处理 ${result.totalCount} 条链接，成功 ${result.successCount} 条，失败 ${result.failedCount} 条。`),
      failedResults.length > 0
        ? h('div', [
            h('div', { style: 'margin-bottom: 8px; font-weight: 500;' }, '失败明细：'),
            ...failedResults.slice(0, 10).map((item) =>
              h('div', { style: 'margin-bottom: 4px; word-break: break-all;' }, `${item.detailUrl}：${item.message}`),
            ),
            failedResults.length > 10
              ? h('div', { style: 'color: var(--color-text-secondary);' }, `其余 ${failedResults.length - 10} 条请分批重试。`)
              : null,
          ])
        : h('div', { style: 'color: var(--color-text-secondary);' }, '全部链接已成功入库。'),
    ]),
  });
};

const handleSubmit = async (): Promise<void> => {
  const { urls, invalidUrls } = parseDetailUrls();
  if (!urls.length && !invalidUrls.length) {
    message.warning('请至少输入一个闲鱼商品链接');
    return;
  }
  if (invalidUrls.length > 0) {
    message.warning(`存在 ${invalidUrls.length} 条无效链接，请检查后重试`);
    return;
  }

  submitting.value = true;
  try {
    const result = await submitSourceInventoryManualImport({ detailUrls: urls });
    showImportSummary(result);
    if (result.successCount > 0) {
      emit('success');
    }
    if (result.failedCount === 0) {
      visible.value = false;
      detailUrlsText.value = '';
      return;
    }
    detailUrlsText.value = result.results
      .filter((item) => !item.success)
      .map((item) => item.detailUrl)
      .join('\n');
  } finally {
    submitting.value = false;
  }
};

defineExpose({ open });
</script>

<template>
  <a-modal
    v-model:open="visible"
    title="人工入库"
    :confirm-loading="submitting"
    ok-text="解析并入库"
    @ok="handleSubmit"
  >
    <a-form layout="vertical">
      <a-form-item label="闲鱼商品链接" required>
        <a-textarea
          v-model:value="detailUrlsText"
          placeholder="一行一个链接，支持 goofish.com 或 2.taobao.com 商品链接"
          :disabled="submitting"
          :rows="8"
        />
      </a-form-item>
    </a-form>
    <p class="hint">
      系统会按行批量解析并直接写入货源库。已成功的链接会立即入库，失败的链接会保留在输入框中便于重试。
    </p>
  </a-modal>
</template>

<style scoped>
.hint {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
