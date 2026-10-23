<script setup lang="ts">
import { ref, reactive } from 'vue';
import { message } from 'ant-design-vue';
import type { FormInstance } from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import type { Dayjs } from 'dayjs';
import { createSourceTask, type SourceTaskCreateReqVO } from '#/api/app/xianyu/source';
import { yuanToFen } from '#/utils/format';

defineOptions({ name: 'CreateTaskModal' });

const emit = defineEmits<{ (e: 'refresh'): void }>();

// ==================== 弹窗状态 ====================

const visible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

type FilterRuleType = 'viewCount' | 'wantCount';
type FilterBound = number | undefined;

interface FilterRuleRow {
  id: number;
  type?: FilterRuleType;
  min: FilterBound;
  max: FilterBound;
}

const filterRuleOptions: Array<{ label: string; value: FilterRuleType }> = [
  { label: '浏览量', value: 'viewCount' },
  { label: '想要量', value: 'wantCount' },
];

const filterRules = ref<FilterRuleRow[]>([]);
let nextFilterRuleId = 1;

// ==================== 表单数据 ====================

const formData = reactive({
  productName: '',
  filterMinPriceYuan: undefined as number | undefined,
  filterMaxPriceYuan: undefined as number | undefined,
  fetchCount: 5,
  triggerMode: 0,
  scheduleTimeDayjs: undefined as Dayjs | undefined,
});

// ==================== 表单校验规则 ====================

const rules: Record<string, Rule[]> = {
  productName: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  fetchCount: [{ required: true, message: '请输入获取条数', trigger: 'blur' }],
};

// ==================== 方法 ====================

function open() {
  visible.value = true;
}

function handleCancel() {
  visible.value = false;
  resetForm();
}

function resetForm() {
  formData.productName = '';
  formData.filterMinPriceYuan = undefined;
  formData.filterMaxPriceYuan = undefined;
  formData.fetchCount = 5;
  formData.triggerMode = 0;
  formData.scheduleTimeDayjs = undefined;
  filterRules.value = [];
  nextFilterRuleId = 1;
  formRef.value?.clearValidate();
}

function addFilterRule() {
  if (filterRules.value.length >= filterRuleOptions.length) return;
  filterRules.value.push({ id: nextFilterRuleId++, min: undefined, max: undefined });
}

function removeFilterRule(id: number) {
  filterRules.value = filterRules.value.filter((rule) => rule.id !== id);
}

function getFilterRuleLabel(type?: FilterRuleType) {
  return filterRuleOptions.find((option) => option.value === type)?.label || '';
}

function isFilterRuleOptionDisabled(type: FilterRuleType, currentRuleId: number) {
  return filterRules.value.some((rule) => rule.id !== currentRuleId && rule.type === type);
}

function validateFilterRuleRanges() {
  for (const rule of filterRules.value) {
    if (rule.min == null && rule.max == null) continue;
    if (!rule.type) {
      message.error('请选择规则类型');
      return false;
    }
    if (rule.min != null && rule.max != null && rule.min > rule.max) {
      message.error(`${getFilterRuleLabel(rule.type)}最小值不能大于最大值`);
      return false;
    }
  }
  return true;
}

async function handleSubmit() {
  try {
    await formRef.value?.validate();
  } catch {
    return;
  }
  if (!validateFilterRuleRanges()) return;

  const req: SourceTaskCreateReqVO = {
    productName: formData.productName,
    fetchCount: formData.fetchCount,
    triggerMode: formData.triggerMode,
  };

  if (formData.filterMinPriceYuan !== undefined && formData.filterMinPriceYuan !== null) {
    req.filterMinPrice = yuanToFen(formData.filterMinPriceYuan);
  }
  if (formData.filterMaxPriceYuan !== undefined && formData.filterMaxPriceYuan !== null) {
    req.filterMaxPrice = yuanToFen(formData.filterMaxPriceYuan);
  }
  if (formData.triggerMode === 1 && formData.scheduleTimeDayjs) {
    req.scheduleTime = formData.scheduleTimeDayjs.format('HH:mm');
  }
  for (const rule of filterRules.value) {
    if (rule.min == null && rule.max == null) continue;
    if (rule.type === 'viewCount') {
      if (rule.min != null) req.filterViewCountMin = rule.min;
      if (rule.max != null) req.filterViewCountMax = rule.max;
    } else if (rule.type === 'wantCount') {
      if (rule.min != null) req.filterWantCountMin = rule.min;
      if (rule.max != null) req.filterWantCountMax = rule.max;
    }
  }

  submitting.value = true;
  try {
    await createSourceTask(req);
    message.success('创建成功');
    visible.value = false;
    resetForm();
    emit('refresh');
  } finally {
    submitting.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <a-modal
    :open="visible"
    title="创建采集任务"
    :width="520"
    :confirm-loading="submitting"
    :destroy-on-close="true"
    @cancel="handleCancel"
    @ok="handleSubmit"
  >
    <template #footer>
      <a-space>
        <a-button @click="handleCancel">取消</a-button>
        <a-button type="primary" :loading="submitting" @click="handleSubmit">提交</a-button>
      </a-space>
    </template>

    <a-form
      ref="formRef"
      :model="formData"
      :rules="rules"
      layout="vertical"
      style="margin-top: 8px;"
    >
      <!-- 商品名称 -->
      <a-form-item label="商品名称" name="productName">
        <a-input
          v-model:value="formData.productName"
          placeholder="请输入商品名称"
          :maxlength="128"
          allow-clear
        />
      </a-form-item>

      <!-- 价格范围 -->
      <a-form-item label="价格范围（元）">
        <div style="display: flex; align-items: center; gap: 8px;">
          <a-input-number
            v-model:value="formData.filterMinPriceYuan"
            placeholder="最低价"
            :min="0"
            :precision="2"
            style="width: 180px;"
          />
          <span>~</span>
          <a-input-number
            v-model:value="formData.filterMaxPriceYuan"
            placeholder="最高价"
            :min="0"
            :precision="2"
            style="width: 180px;"
          />
        </div>
      </a-form-item>

      <!-- 获取条数 -->
      <a-form-item label="获取条数" name="fetchCount">
        <a-input-number
          v-model:value="formData.fetchCount"
          :min="1"
          :max="100"
          :precision="0"
          style="width: 180px;"
        />
      </a-form-item>

      <!-- 任务类型 -->
      <a-form-item label="任务类型" name="triggerMode">
        <a-radio-group v-model:value="formData.triggerMode">
          <a-radio :value="0">一次性</a-radio>
          <a-radio :value="1">每日定时</a-radio>
        </a-radio-group>
      </a-form-item>

      <!-- 执行时间（仅每日定时显示） -->
      <a-form-item
        v-if="formData.triggerMode === 1"
        label="执行时间"
        name="scheduleTimeDayjs"
      >
        <a-time-picker
          v-model:value="formData.scheduleTimeDayjs"
          format="HH:mm"
          :minute-step="1"
          placeholder="请选择执行时间"
          style="width: 180px;"
        />
      </a-form-item>

      <!-- 浏览量 / 想要量过滤规则 -->
      <div class="filter-rule-section">
        <div class="filter-rule-title">规则</div>
        <div class="filter-rule-hint">非必填，筛选符合条件的结果</div>

        <div v-for="rule in filterRules" :key="rule.id" class="filter-rule-row">
          <a-select v-model:value="rule.type" placeholder="规则类型" class="filter-rule-type">
            <a-select-option
              v-for="option in filterRuleOptions"
              :key="option.value"
              :value="option.value"
              :disabled="isFilterRuleOptionDisabled(option.value, rule.id)"
            >
              {{ option.label }}
            </a-select-option>
          </a-select>
          <a-input-number
            v-model:value="rule.min"
            placeholder="最小值"
            :min="0"
            :precision="0"
            class="filter-rule-input"
          />
          <span class="filter-rule-separator">～</span>
          <a-input-number
            v-model:value="rule.max"
            placeholder="最大值"
            :min="0"
            :precision="0"
            class="filter-rule-input"
          />
          <a-button type="link" size="small" danger @click="removeFilterRule(rule.id)">
            删除
          </a-button>
        </div>

        <a-button
          type="dashed"
          size="small"
          :disabled="filterRules.length >= filterRuleOptions.length"
          @click="addFilterRule"
        >
          ＋ 添加规则
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>

<style scoped>
.filter-rule-section {
  margin-bottom: 8px;
}

.filter-rule-title {
  color: var(--color-text-primary);
  font-weight: 500;
}

.filter-rule-hint {
  margin: 4px 0 12px;
  color: var(--color-text-muted);
}

.filter-rule-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.filter-rule-type {
  width: 120px;
}

.filter-rule-input {
  flex: 1;
  min-width: 0;
}

.filter-rule-separator {
  color: var(--color-text-muted);
}
</style>
