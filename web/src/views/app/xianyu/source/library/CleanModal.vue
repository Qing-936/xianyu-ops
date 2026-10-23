<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { Checkbox, Input, Modal, Select, Spin, Tag, message } from 'ant-design-vue';
import {
  recommendCleanSkills,
  submitCleanTask,
  type SourceCleanCategoryRespVO,
  type SourceCleanSkillRespVO,
  type SourceInventoryCleanRecommendItem,
} from '#/api/app/xianyu/source';

defineOptions({ name: 'CleanModal' });

type CleanField = 'images' | 'title' | 'description';
const cleanFieldOrder: CleanField[] = ['images', 'title', 'description'];
const cleanFieldLabels: Record<CleanField, string> = {
  images: '头图',
  title: '标题',
  description: '描述',
};

interface CleanRow extends SourceInventoryCleanRecommendItem {
  categoryCode: string;
  routingCategoryCode: string;
  selectedSkills: Partial<Record<CleanField, string>>;
  referenceOriginalImage: boolean;
}

const emit = defineEmits<{
  success: [];
}>();

const visible = ref(false);
const submitting = ref(false);
const recommending = ref(false);
const inventoryIds = ref<number[]>([]);
const rows = ref<CleanRow[]>([]);
const skills = ref<SourceCleanSkillRespVO[]>([]);
const categories = ref<SourceCleanCategoryRespVO[]>([]);
const recommendationTraceId = ref('');
const catalogVersion = ref('');
const recommendationRun = ref(0);

const cleanFields = reactive<Record<CleanField, boolean>>({
  images: true,
  title: true,
  description: true,
});

/** 各清洗字段的补充要求（可选，允许为空） */
const requirements = reactive<Record<CleanField, string>>({
  images: '',
  title: '',
  description: '',
});

const requirementPlaceholders: Record<CleanField, string> = {
  images: '头图要求（可选），如：地区改深圳',
  title: '标题要求（可选），如：包含「上门回收」',
  description: '描述要求（可选），如：不要提品牌',
};

/** 与后端 @Size(max = 500) 保持一致 */
const requirementMaxLength = 500;

const selectedCount = computed(() => inventoryIds.value.length);
const selectedFields = computed(() => cleanFieldOrder.filter((field) => cleanFields[field]));
const tableGridTemplate = computed(() =>
  `minmax(210px, 1.4fr) 150px ${selectedFields.value.map(() => 'minmax(170px, 1fr)').join(' ')} 52px`,
);

const categoryOptions = computed(() =>
  categories.value.map((category) => ({
    label: category.categoryName,
    value: category.categoryCode,
  })),
);

const isCompatible = (skill: SourceCleanSkillRespVO, categoryCode: string) =>
  !skill.categoryScopes?.length || (categoryCode === 'physical'
    ? skill.categoryScopes.some((scope) => scope.startsWith('physical.'))
    : skill.categoryScopes.includes(categoryCode));

const canReferenceOriginalImage = (row: CleanRow) =>
  Boolean(cleanFields.images && row.hasOriginalImage);

const skillOptions = (row: CleanRow, field: CleanField) => {
  const recommendedCode = row.recommendations?.[field]?.skillCode;
  return skills.value
    .filter((skill) => skill.skillType === field && isCompatible(skill, row.categoryCode))
    .sort((left, right) => {
      const recommendationOrder = Number(right.skillCode === recommendedCode)
        - Number(left.skillCode === recommendedCode);
      return recommendationOrder || (left.sortOrder ?? 999) - (right.sortOrder ?? 999);
    })
    .map((skill) => ({
      label: `${skill.skillCode === recommendedCode ? '推荐 · ' : ''}${skill.skillName}（${skill.version}）`,
      value: skill.skillCode,
      title: skill.skillCode === recommendedCode
        ? `AI 推荐：${row.recommendations?.[field]?.reason || '未提供推荐理由'}`
        : skill.description,
    }));
};

const applyRecommendation = (item: SourceInventoryCleanRecommendItem): CleanRow => {
  const recommendation = item.recommendations ?? {};
  const selectedSkills: Partial<Record<CleanField, string>> = {};
  for (const field of cleanFieldOrder) {
    const code = recommendation[field]?.skillCode;
    if (code) selectedSkills[field] = code;
  }
  const routingCategoryCode = item.routingCategory?.categoryCode ?? '';
  return {
    ...item,
    categoryCode: routingCategoryCode.startsWith('physical.') ? 'physical' : routingCategoryCode,
    routingCategoryCode,
    selectedSkills,
    referenceOriginalImage: Boolean(item.referenceOriginalImageRecommended && item.hasOriginalImage),
  };
};

const loadRecommendations = async (ids: number[]) => {
  const run = ++recommendationRun.value;
  recommending.value = true;
  try {
    const result = await recommendCleanSkills({ inventoryIds: ids });
    if (run !== recommendationRun.value) return;
    skills.value = result.skills ?? [];
    categories.value = result.categories ?? [];
    inventoryIds.value = result.items.map((item) => item.inventoryId);
    rows.value = result.items.map(applyRecommendation);
    recommendationTraceId.value = result.recommendationTraceId;
    catalogVersion.value = result.catalogVersion;
    visible.value = true;
  } catch {
    if (run === recommendationRun.value) {
      rows.value = [];
      message.error('加载 Skill 推荐失败，请稍后重试');
    }
  } finally {
    if (run === recommendationRun.value) recommending.value = false;
  }
};

const open = (ids: number[]) => {
  if (recommending.value) return;
  inventoryIds.value = [...ids];
  rows.value = [];
  skills.value = [];
  categories.value = [];
  recommendationTraceId.value = '';
  catalogVersion.value = '';
  cleanFields.images = true;
  cleanFields.title = true;
  cleanFields.description = true;
  requirements.images = '';
  requirements.title = '';
  requirements.description = '';
  void loadRecommendations([...ids]);
};

const handleClose = () => {
  recommendationRun.value += 1;
  visible.value = false;
};

const handleCategoryChange = (row: CleanRow, categoryCode: string) => {
  row.categoryCode = categoryCode;
  if (categoryCode !== 'physical') row.routingCategoryCode = categoryCode;
  else if (!row.routingCategoryCode.startsWith('physical.')) row.routingCategoryCode = '';
  for (const field of cleanFieldOrder) {
    const selected = row.selectedSkills[field];
    if (selected && !skills.value.some((skill) => skill.skillType === field
      && skill.skillCode === selected && isCompatible(skill, categoryCode))) {
      delete row.selectedSkills[field];
    }
  }
  if (!canReferenceOriginalImage(row)) row.referenceOriginalImage = false;
};

const handleSkillChange = (row: CleanRow, field: CleanField, skillCode: string) => {
  row.selectedSkills[field] = skillCode;
  if (row.categoryCode === 'physical') {
    const selected = Object.values(row.selectedSkills)
      .map((code) => skills.value.find((skill) => skill.skillCode === code))
      .filter((skill): skill is SourceCleanSkillRespVO => Boolean(skill));
    let candidates = [...new Set(skills.value
      .flatMap((skill) => skill.categoryScopes ?? [])
      .filter((scope) => scope.startsWith('physical.')))];
    for (const skill of selected) {
      const scopes = (skill.categoryScopes ?? []).filter((scope) => scope.startsWith('physical.'));
      if (scopes.length) candidates = candidates.filter((candidate) => scopes.includes(candidate));
    }
    row.routingCategoryCode = candidates.includes(row.routingCategoryCode)
      ? row.routingCategoryCode : candidates.length === 1 ? candidates[0] : '';
  }
  if (!canReferenceOriginalImage(row)) row.referenceOriginalImage = false;
};

const removeRow = (inventoryId: number) => {
  rows.value = rows.value.filter((row) => row.inventoryId !== inventoryId);
  inventoryIds.value = inventoryIds.value.filter((id) => id !== inventoryId);
};

const firstInvalidMessage = computed(() => {
  if (!selectedFields.value.length) return '请至少选择一项清洗字段';
  for (const row of rows.value) {
    if (!row.categoryCode) return `请选择「${row.title || `商品 ${row.inventoryId}`}」的分类`;
    for (const field of selectedFields.value) {
      if (!row.selectedSkills[field]) return `请选择「${row.title || `商品 ${row.inventoryId}`}」的${cleanFieldLabels[field]} Skill`;
    }
  }
  return '';
});

const canSubmit = computed(() => Boolean(rows.value.length && !recommending.value && !submitting.value && !firstInvalidMessage.value));

const handleSubmit = async () => {
  if (firstInvalidMessage.value) {
    message.warning(firstInvalidMessage.value);
    return;
  }
  try {
    submitting.value = true;
    const cleanTypes = [...selectedFields.value];
    await submitCleanTask({
      clientRequestId: crypto.randomUUID(),
      recommendationTraceId: recommendationTraceId.value || undefined,
      catalogVersion: catalogVersion.value || undefined,
      requirementImages: requirements.images.trim(),
      requirementTitle: requirements.title.trim(),
      requirementDescription: requirements.description.trim(),
      items: rows.value.map((row) => ({
        inventoryId: row.inventoryId,
        categoryCode: row.categoryCode === 'physical'
          ? row.routingCategoryCode || 'physical' : row.categoryCode,
        cleanTypes,
        skills: Object.fromEntries(cleanTypes.map((field) => [field, row.selectedSkills[field]!])),
        referenceOriginalImage: Boolean(row.referenceOriginalImage && cleanFields.images),
      })),
    });
    message.success(`已成功提交 ${rows.value.length} 条货源的清洗任务，请在列表中查看进度`);
    visible.value = false;
    emit('success');
  } catch {
    message.error('提交清洗任务失败');
  } finally {
    submitting.value = false;
  }
};

defineExpose({ open });
</script>

<template>
  <Modal
    :open="recommending"
    :footer="null"
    :closable="false"
    :keyboard="false"
    :mask-closable="false"
    :width="340"
    :z-index="2000"
    centered
  >
    <div class="recommendation-loading">
      <Spin size="large" tip="智能推荐最优清洗风格，请稍后..." />
    </div>
  </Modal>
  <Modal
    v-model:open="visible"
    :title="`AI 清洗（已选 ${selectedCount} 条）`"
    :width="1120"
    :confirm-loading="submitting"
    :ok-button-props="{ disabled: !canSubmit }"
    :mask-closable="false"
    ok-text="开始清洗"
    cancel-text="取消"
    @ok="handleSubmit"
    @cancel="handleClose"
  >
    <Spin :spinning="recommending" tip="正在分析商品并推荐 Skill…">
      <div class="clean-fields">
        <span class="field-label">清洗字段 <span class="field-hint">（默认全选）</span></span>
        <div v-for="field in cleanFieldOrder" :key="field" class="clean-field-item">
          <Checkbox v-model:checked="cleanFields[field]">{{ cleanFieldLabels[field] }}</Checkbox>
          <Input.TextArea
            v-if="cleanFields[field]"
            v-model:value="requirements[field]"
            :placeholder="requirementPlaceholders[field]"
            :maxlength="requirementMaxLength"
            :auto-size="{ minRows: 2, maxRows: 4 }"
            class="requirement-input"
            allow-clear
          />
        </div>
      </div>

      <div v-if="rows.length" class="clean-table">
        <div class="clean-table__head" :style="{ gridTemplateColumns: tableGridTemplate }">
          <span>商品</span>
          <span>分类</span>
          <span v-if="cleanFields.images">头图 Skill</span>
          <span v-if="cleanFields.title">标题 Skill</span>
          <span v-if="cleanFields.description">描述 Skill</span>
          <span>操作</span>
        </div>
        <div
          v-for="row in rows"
          :key="row.inventoryId"
          class="clean-table__row"
          :style="{ gridTemplateColumns: tableGridTemplate }"
        >
          <div class="product-cell">
            <img v-if="row.mainImageUrl" :src="row.mainImageUrl" alt="商品原图" class="product-cell__image">
            <div class="product-cell__title" :title="row.title">{{ row.title || `商品 ${row.inventoryId}` }}</div>
            <Checkbox
              v-if="cleanFields.images && row.hasOriginalImage"
              v-model:checked="row.referenceOriginalImage"
              :disabled="!canReferenceOriginalImage(row)"
              class="reference-checkbox"
            >
              参考原图
            </Checkbox>
            <span v-else-if="cleanFields.images" class="reference-empty">暂无可参考原图</span>
          </div>
          <Select
            :value="row.categoryCode"
            :options="categoryOptions"
            placeholder="请选择分类"
            class="skill-select"
            @change="(value) => typeof value === 'string' && handleCategoryChange(row, value)"
          />
          <Select
            v-if="cleanFields.images"
            :value="row.selectedSkills.images"
            :options="skillOptions(row, 'images')"
            placeholder="请选择头图 Skill"
            class="skill-select"
            @change="(value) => typeof value === 'string' && handleSkillChange(row, 'images', value)"
          />
          <Select
            v-if="cleanFields.title"
            :value="row.selectedSkills.title"
            :options="skillOptions(row, 'title')"
            placeholder="请选择标题 Skill"
            class="skill-select"
            @change="(value) => typeof value === 'string' && handleSkillChange(row, 'title', value)"
          />
          <Select
            v-if="cleanFields.description"
            :value="row.selectedSkills.description"
            :options="skillOptions(row, 'description')"
            placeholder="请选择描述 Skill"
            class="skill-select"
            @change="(value) => typeof value === 'string' && handleSkillChange(row, 'description', value)"
          />
          <button type="button" class="remove-button" @click="removeRow(row.inventoryId)">移除</button>
          <div v-if="row.warnings?.length" class="row-warning">
            <Tag color="orange">需确认</Tag>{{ row.warnings.join('；') }}
          </div>
        </div>
      </div>
      <div v-else-if="!recommending" class="empty-state">暂无可清洗商品</div>
    </Spin>
  </Modal>
</template>

<style scoped>
.recommendation-loading {
  display: flex;
  min-height: 96px;
  align-items: center;
  justify-content: center;
}

.recommendation-loading :deep(.ant-spin-text) {
  padding-top: 12px;
}

.clean-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 24px;
  margin: 4px 0 18px;
}

.clean-field-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.requirement-input {
  width: 180px;
}

.field-label {
  color: var(--color-text-primary);
  font-size: 15px;
  margin-right: 4px;
}

.field-hint {
  color: var(--color-text-muted);
  font-size: 12px;
}

.clean-table {
  overflow-x: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.clean-table__head,
.clean-table__row {
  display: grid;
  gap: 12px;
  align-items: center;
  min-width: 980px;
  padding: 12px 14px;
}

.clean-table__head {
  color: var(--color-text-secondary);
  background: var(--color-surface-alt);
  font-size: 13px;
  font-weight: 600;
}

.clean-table__row {
  position: relative;
  border-top: 1px solid var(--color-border);
}

.product-cell {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.product-cell__image {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  object-fit: cover;
}

.product-cell__title {
  overflow: hidden;
  color: var(--color-text-primary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-checkbox {
  grid-column: 2;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.reference-empty {
  grid-column: 2;
  color: var(--color-text-muted);
  font-size: 12px;
}

.skill-select {
  width: 100%;
}

.remove-button {
  padding: 0;
  border: 0;
  color: var(--color-error, #d9363e);
  background: transparent;
  cursor: pointer;
}

.row-warning {
  grid-column: 1 / -1;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.empty-state {
  padding: 48px 0;
  color: var(--color-text-muted);
  text-align: center;
}
</style>
