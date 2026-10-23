<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { createSourceBlockedSeller } from '#/api/app/xianyu/source';
import { BLOCK_SELLER_CUSTOM_REASON, BLOCK_SELLER_PRESET_REASONS } from '../block-reasons';

defineOptions({ name: 'BlockSellerModal' });

const emit = defineEmits<{ success: [] }>();

// ==================== 弹窗状态 ====================

const visible = ref(false);
const submitting = ref(false);
const sellerName = ref('');
const detailId = ref<number | undefined>(undefined);
const sourceTaskId = ref<number | undefined>(undefined);

// ==================== 拉黑原因 ====================

const formData = reactive({
  reason: '' as string,
  customReason: '',
});

/** 最终生效的拉黑原因 */
const finalReason = computed(() => {
  if (formData.reason === BLOCK_SELLER_CUSTOM_REASON) return formData.customReason.trim();
  return formData.reason;
});

// ==================== 方法 ====================

interface OpenOptions {
  sellerName?: string;
  detailId?: number;
  sourceTaskId?: number;
}

function open(options: OpenOptions = {}) {
  sellerName.value = options.sellerName || '';
  detailId.value = options.detailId;
  sourceTaskId.value = options.sourceTaskId;
  resetForm();
  visible.value = true;
}

function resetForm() {
  formData.reason = '';
  formData.customReason = '';
}

function handleCancel() {
  visible.value = false;
  resetForm();
}

async function handleConfirm() {
  if (!finalReason.value) {
    message.warning(
      formData.reason === BLOCK_SELLER_CUSTOM_REASON ? '请填写拉黑原因' : '请选择或填写拉黑原因',
    );
    return;
  }
  submitting.value = true;
  try {
    await createSourceBlockedSeller({
      sellerName: sellerName.value || undefined,
      detailId: detailId.value,
      sourceTaskId: sourceTaskId.value,
      blockReason: finalReason.value,
    });
    visible.value = false;
    message.success(`已拉黑卖家${sellerName.value ? `「${sellerName.value}」` : ''}`);
    resetForm();
    emit('success');
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : '拉黑失败，请稍后重试';
    message.error(errorMessage);
  } finally {
    submitting.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <a-modal
    v-model:open="visible"
    title="拉黑卖家"
    :width="480"
    :mask-closable="false"
    ok-text="确认拉黑"
    cancel-text="取消"
    :confirm-loading="submitting"
    @ok="handleConfirm"
    @cancel="handleCancel"
  >
    <div style="margin-bottom: 16px">
      <div style="font-weight: 500; margin-bottom: 8px; color: var(--color-text-primary)">
        卖家
      </div>
      <div style="color: var(--color-text-secondary)">
        {{ sellerName || '-' }}
      </div>
    </div>

    <div>
      <div style="font-weight: 500; margin-bottom: 8px; color: var(--color-text-primary)">
        拉黑原因
      </div>
      <a-radio-group v-model:value="formData.reason" style="display: flex; flex-direction: column; gap: 8px">
        <a-radio v-for="reason in BLOCK_SELLER_PRESET_REASONS" :key="reason" :value="reason">
          {{ reason }}
        </a-radio>
        <a-radio :value="BLOCK_SELLER_CUSTOM_REASON">其他原因</a-radio>
      </a-radio-group>
      <a-textarea
        v-if="formData.reason === BLOCK_SELLER_CUSTOM_REASON"
        v-model:value="formData.customReason"
        :rows="3"
        :maxlength="200"
        show-count
        placeholder="请填写拉黑原因"
        style="margin-top: 8px"
      />
    </div>
  </a-modal>
</template>
