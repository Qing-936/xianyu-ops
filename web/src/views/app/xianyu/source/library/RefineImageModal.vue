<script setup lang="ts">
import { computed, ref } from 'vue';
import { Button, Image, Input, Modal, Spin, message } from 'ant-design-vue';
import {
  applySourceInventoryRefineImage,
  previewSourceInventoryRefineImage,
  type SourceInventoryRespVO,
} from '#/api/app/xianyu/source';

defineOptions({ name: 'RefineImageModal' });

const emit = defineEmits<{
  success: [];
}>();

const TextArea = Input.TextArea;

const visible = ref(false);
const record = ref<SourceInventoryRespVO | null>(null);
const prompt = ref('');
const generating = ref(false);
const applying = ref(false);
const previewImageUrl = ref('');

const originalImageUrl = computed(() => record.value?.picUrl || '');

const canGenerate = computed(
  () => Boolean(record.value) && prompt.value.trim().length > 0 && !generating.value && !applying.value,
);
const canApply = computed(() => Boolean(previewImageUrl.value) && !generating.value && !applying.value);

const open = (target: SourceInventoryRespVO) => {
  record.value = target;
  prompt.value = '';
  previewImageUrl.value = '';
  visible.value = true;
};

const handleClose = () => {
  visible.value = false;
};

const handleGenerate = async () => {
  if (!record.value || !prompt.value.trim()) {
    message.warning('请输入微调指令');
    return;
  }
  generating.value = true;
  previewImageUrl.value = '';
  try {
    const result = await previewSourceInventoryRefineImage({
      inventoryId: record.value.id,
      prompt: prompt.value.trim(),
    });
    previewImageUrl.value = result.imageUrl;
    if (!result.imageUrl) {
      message.error('未生成有效图片，请调整指令后重试');
    }
  } catch {
    message.error('图片微调失败，请稍后重试');
  } finally {
    generating.value = false;
  }
};

const handleApply = async () => {
  if (!record.value || !previewImageUrl.value) return;
  applying.value = true;
  try {
    await applySourceInventoryRefineImage(record.value.id);
    message.success('已替换为微调后的主图');
    visible.value = false;
    emit('success');
  } catch {
    message.error('替换主图失败，请稍后重试');
  } finally {
    applying.value = false;
  }
};

defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    title="图片微调"
    :width="760"
    :mask-closable="false"
    :footer="null"
    @cancel="handleClose"
  >
    <div v-if="record" class="refine-body">
      <div class="refine-product" :title="record.title">
        {{ record.title || `货源 ${record.id}` }}
      </div>

      <div class="refine-images">
        <div class="refine-image-card">
          <div class="refine-image-label">当前主图</div>
          <div class="refine-image-frame">
            <Image v-if="originalImageUrl" :src="originalImageUrl" :preview="true" />
            <span v-else class="refine-image-empty">暂无主图</span>
          </div>
        </div>
        <div class="refine-image-card">
          <div class="refine-image-label">微调预览</div>
          <div class="refine-image-frame">
            <Spin v-if="generating" tip="正在生成，请稍候…">
              <div class="refine-image-placeholder" />
            </Spin>
            <Image
              v-else-if="previewImageUrl"
              :src="previewImageUrl"
              :preview="true"
            />
            <span v-else class="refine-image-empty">输入指令后生成预览</span>
          </div>
        </div>
      </div>

      <div class="refine-field">
        <div class="refine-field-label">微调指令</div>
        <TextArea
          v-model:value="prompt"
          :rows="3"
          :maxlength="500"
          show-count
          placeholder="用自然语言描述要修改的内容，例如：把价格改为 0.01 美刀"
        />
      </div>

      <div class="refine-actions">
        <Button
          type="primary"
          :loading="generating"
          :disabled="!canGenerate"
          @click="handleGenerate"
        >
          {{ previewImageUrl ? '重新生成' : '生成预览' }}
        </Button>
        <Button
          type="primary"
          :loading="applying"
          :disabled="!canApply"
          @click="handleApply"
        >
          确认替换主图
        </Button>
      </div>
      <div class="refine-tip">
        预览图 30 分钟内有效，确认后将替换货源主图（images 首图与 picUrl）。
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.refine-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.refine-product {
  overflow: hidden;
  color: var(--color-text-primary);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.refine-images {
  display: flex;
  gap: 16px;
}

.refine-image-card {
  flex: 1;
  min-width: 0;
}

.refine-image-label {
  margin-bottom: 8px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.refine-image-frame {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 240px;
  padding: 8px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
}

/* Image 组件的外层容器需撑满卡片，尺寸约束要落在图片本体上，
   否则图片按原始比例溢出 240px 的框后被裁切，出现「展示不全」 */
.refine-image-frame :deep(.ant-image) {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
}

.refine-image-frame :deep(.ant-image-img) {
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.refine-image-placeholder {
  width: 160px;
  height: 160px;
}

.refine-image-empty {
  color: var(--color-text-muted);
  font-size: 12px;
}

.refine-field-label {
  margin-bottom: 8px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.refine-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.refine-tip {
  color: var(--color-text-muted);
  font-size: 12px;
}

@media (max-width: 768px) {
  .refine-images {
    flex-direction: column;
  }
}
</style>
