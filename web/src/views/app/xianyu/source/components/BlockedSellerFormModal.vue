<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import {
  createSourceBlockedSeller,
  updateSourceBlockedSeller,
  type SourceBlockedSellerRespVO,
} from '#/api/app/xianyu/source';
import { BLOCK_SELLER_CUSTOM_REASON, BLOCK_SELLER_PRESET_REASONS } from '../block-reasons';

defineOptions({ name: 'BlockedSellerFormModal' });

const emit = defineEmits<{ success: [] }>();

// ==================== 弹窗状态 ====================

const visible = ref(false);
const submitting = ref(false);
const mode = ref<'create' | 'edit'>('create');
const currentRecord = ref<SourceBlockedSellerRespVO | null>(null);

const formData = reactive({
  sellerId: '',
  sellerName: '',
  reason: '' as string,
  customReason: '',
});

const title = computed(() => (mode.value === 'create' ? '新增拉黑' : '编辑拉黑原因'));
const isEdit = computed(() => mode.value === 'edit');

/** 最终生效的拉黑原因 */
const finalReason = computed(() => {
  if (formData.reason === BLOCK_SELLER_CUSTOM_REASON) return formData.customReason.trim();
  return formData.reason;
});

// ==================== 方法 ====================

function resetForm() {
  formData.sellerId = '';
  formData.sellerName = '';
  formData.reason = '';
  formData.customReason = '';
}

function openCreate() {
  mode.value = 'create';
  currentRecord.value = null;
  resetForm();
  visible.value = true;
}

function openEdit(record: SourceBlockedSellerRespVO) {
  mode.value = 'edit';
  currentRecord.value = record;
  resetForm();
  formData.sellerId = record.sellerId || '';
  formData.sellerName = record.sellerName || '';
  // 已有原因命中预置项则选中，否则落到「其他原因」并回填
  if (record.blockReason && BLOCK_SELLER_PRESET_REASONS.includes(record.blockReason)) {
    formData.reason = record.blockReason;
  } else if (record.blockReason) {
    formData.reason = BLOCK_SELLER_CUSTOM_REASON;
    formData.customReason = record.blockReason;
  }
  visible.value = true;
}

function handleCancel() {
  visible.value = false;
  resetForm();
}

async function handleConfirm() {
  if (!isEdit.value && !formData.sellerId.trim() && !formData.sellerName.trim()) {
    message.warning('请填写卖家名称或卖家ID');
    return;
  }
  if (!finalReason.value) {
    message.warning(formData.reason === BLOCK_SELLER_CUSTOM_REASON ? '请填写拉黑原因' : '请选择或填写拉黑原因');
    return;
  }
  submitting.value = true;
  try {
    if (isEdit.value && currentRecord.value) {
      await updateSourceBlockedSeller({
        id: currentRecord.value.id,
        blockReason: finalReason.value,
      });
      message.success('已更新拉黑原因');
    } else {
      await createSourceBlockedSeller({
        sellerId: formData.sellerId.trim() || undefined,
        sellerName: formData.sellerName.trim() || undefined,
        blockReason: finalReason.value,
      });
      message.success('已拉黑该卖家');
    }
    visible.value = false;
    resetForm();
    emit('success');
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : '操作失败，请稍后重试';
    message.error(errorMessage);
  } finally {
    submitting.value = false;
  }
}

defineExpose({ openCreate, openEdit });
</script>

<template>
  <a-modal
    v-model:open="visible"
    :title="title"
    :width="480"
    :mask-closable="false"
    :ok-text="isEdit ? '保存' : '确认拉黑'"
    cancel-text="取消"
    :confirm-loading="submitting"
    @ok="handleConfirm"
    @cancel="handleCancel"
  >
    <a-form layout="vertical">
      <a-form-item v-if="isEdit" label="卖家">
        <div style="color: var(--color-text-secondary)">
          {{ formData.sellerName || '-' }}
          <span v-if="formData.sellerId" style="margin-left: 8px; color: var(--color-text-muted)">
            ID：{{ formData.sellerId }}
          </span>
        </div>
      </a-form-item>

      <template v-else>
        <a-form-item label="卖家名称/昵称">
          <a-input v-model:value="formData.sellerName" placeholder="请输入卖家名称或昵称" allow-clear />
        </a-form-item>
        <a-form-item label="卖家ID">
          <a-input v-model:value="formData.sellerId" placeholder="选填，填写后匹配更精确" allow-clear />
        </a-form-item>
      </template>

      <a-form-item label="拉黑原因">
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
      </a-form-item>
    </a-form>
  </a-modal>
</template>
