<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import dayjs from 'dayjs';
import { message } from 'ant-design-vue';
import {
  getConfigFile,
  getConfigFiles,
  getConfigServices,
  resetConfigFile,
  saveConfigFile,
  type ConfigFileRespVO,
  type ConfigServiceRespVO,
} from '#/api/app/config';

defineOptions({ name: 'AppConfig' });

const TAG_LABELS: Record<string, string> = {
  prompt: '提示词',
  config: '配置',
  template: '模板',
  data: '数据',
};
const TAG_COLORS: Record<string, string> = {
  prompt: 'blue',
  config: 'orange',
  template: 'purple',
  data: 'green',
};

/** 各文件的编辑规则说明（面向运营的中文提示） */
const FILE_RULES: Record<string, string> = {
  'skills.md': `【技能库编写规则】

1. 每条技能以「## 技能名称」开头。
2. 属性行以 @ 开头，必须包含：
   · @编码：英文小写字母 / 数字 / 下划线，全文件不可重复
   · @类型：标题 / 文案 / 头图
   · @适用分类：中文分类名，多个用逗号分隔（需先在 categories.json 中存在）
   · @版本：如 3.1.0
   · @排序：整数，越小越靠前
   · @说明：一句话用途说明
3. 标题 / 文案类必须写 @最大长度（正整数）。
4. 头图类可选 @参考图（是/否，默认「是」）、@图片数量（正整数，默认 1）。
5. 提示词写在 \`\`\`prompt 与 \`\`\` 之间，内容原样保存，支持换行、emoji 和符号。
6. 新增技能：复制任意一条，改 @编码 与各属性，重写提示词即可。
7. 保存时会校验，出错会提示具体行号，不会写入坏数据。

【示例】

## 数字＋核心价值
@编码 title_numeric_value
@类型 标题
@适用分类 电子资料类, 服务代办类, 定制开发类
@版本 3.1.0
@排序 10
@最大长度 30
@目标商品 电子资料, 服务代办
@说明 适用于数量、规格或周期明确的商品

\`\`\`prompt
仅根据输入商品标题和描述生成一条单行商品标题，采用「真实数字/事实＋商品品类＋用户好处」。
\`\`\``,
  'categories.json': `【分类目录编写规则】

1. 必须是合法 JSON。
2. 保留 version 与 categories 字段。
3. categories 每一项包含：
   · category_code：英文编码（如 physical.pet）
   · category_name：中文名称（skills.md 的 @适用分类 引用它）
   · group_name：分组（虚拟商品 / 实物商品）
4. category_name 不可重复。
5. 新增分类后，到 skills.md 给需要的技能 @适用分类 里加上该中文名。
6. 保存时会校验 JSON 格式，格式错误会拒绝保存。

【示例】

{
  "version": "3.1.0",
  "categories": [
    {
      "category_code": "physical.pet",
      "category_name": "宠物用品",
      "group_name": "实物商品"
    }
  ]
}`,
};

/** skills.md 输入 @ 时的属性补全项 */
const ATTR_OPTIONS = [
  { name: '编码', desc: '英文小写/数字/下划线，不可重复' },
  { name: '类型', desc: '标题 / 文案 / 头图' },
  { name: '适用分类', desc: '中文分类名，逗号分隔' },
  { name: '版本', desc: '如 3.1.0' },
  { name: '排序', desc: '整数，越小越靠前' },
  { name: '最大长度', desc: '标题/文案必填' },
  { name: '目标商品', desc: '可选，逗号分隔' },
  { name: '说明', desc: '一句话用途说明' },
  { name: '参考图', desc: '仅头图，是/否，默认是' },
  { name: '图片数量', desc: '仅头图，默认 1' },
];

const services = ref<ConfigServiceRespVO[]>([]);
const activeService = ref<ConfigServiceRespVO>();
const files = ref<ConfigFileRespVO[]>([]);
const activeFile = ref<ConfigFileRespVO>();
const content = ref('');
const savedContent = ref('');
const loading = ref(false);
const saving = ref(false);
const errorText = ref('');
const expandVisible = ref(false);
const ruleVisible = ref(false);
const attrMenuVisible = ref(false);
const attrMenuTop = ref(0);
/** 当前补全浮层属于哪个编辑器：主编辑框 / 放大弹窗 */
const attrMenuTarget = ref<'expand' | 'main'>('main');
/** 键盘导航高亮项下标 */
const attrMenuIndex = ref(0);
/** 原生 textarea 引用（用于插入属性后恢复光标） */
let editorTextarea: HTMLTextAreaElement | null = null;

const dirty = computed(() => content.value !== savedContent.value);
const ruleText = computed(() =>
  activeFile.value ? FILE_RULES[activeFile.value.fileName] ?? '暂无规则说明' : '',
);
/** categories.json 实时格式校验结果（空串 = 合法） */
const jsonError = computed(() => {
  if (activeFile.value?.fileName !== 'categories.json' || !content.value.trim()) return '';
  try {
    JSON.parse(content.value);
    return '';
  } catch (e) {
    return '格式有误：' + (e instanceof Error ? e.message : String(e));
  }
});

async function loadServices() {
  loading.value = true;
  try {
    services.value = await getConfigServices();
    const first = services.value.find((item) => item.enabled);
    if (first) {
      await selectService(first);
    }
  } finally {
    loading.value = false;
  }
}

async function selectService(service: ConfigServiceRespVO) {
  if (!service.enabled || service.serviceCode === activeService.value?.serviceCode) return;
  activeService.value = service;
  errorText.value = '';
  files.value = await getConfigFiles(service.serviceCode);
  if (files.value.length > 0) {
    await selectFile(files.value[0]!);
  } else {
    activeFile.value = undefined;
    content.value = '';
    savedContent.value = '';
  }
}

async function selectFile(file: ConfigFileRespVO) {
  activeFile.value = file;
  errorText.value = '';
  attrMenuVisible.value = false;
  const detail = await getConfigFile(file.serviceCode, file.fileName);
  content.value = detail.content;
  savedContent.value = detail.content;
}

/** 输入 @ 时弹出属性补全（仅 skills.md） */
function handleEditorInput(e: Event, target: 'expand' | 'main') {
  const el = e.target as HTMLTextAreaElement;
  editorTextarea = el;
  if (activeFile.value?.fileName !== 'skills.md') {
    attrMenuVisible.value = false;
    return;
  }
  const pos = el.selectionStart ?? 0;
  const before = content.value.slice(0, pos);
  const lineStart = before.lastIndexOf('\n') + 1;
  // 当前行以 @ 开头且尚未输入空格：正在输入属性名
  if (!/^@\S*$/.test(before.slice(lineStart))) {
    attrMenuVisible.value = false;
    return;
  }
  const style = getComputedStyle(el);
  const lineHeight = Number.parseFloat(style.lineHeight) || 24;
  const paddingTop = Number.parseFloat(style.paddingTop) || 4;
  const lineIndex = before.split('\n').length - 1;
  attrMenuTop.value = paddingTop + (lineIndex + 1) * lineHeight - el.scrollTop;
  attrMenuTarget.value = target;
  // 仅在首次打开时重置高亮，连续输入时保持
  if (!attrMenuVisible.value) {
    attrMenuIndex.value = 0;
  }
  attrMenuVisible.value = true;
}

/** 选中属性项：把当前行的 @xxx 替换为 @属性名 并定位光标 */
function insertAttr(name: string) {
  const el = editorTextarea;
  if (!el) return;
  const pos = el.selectionStart ?? 0;
  const before = content.value.slice(0, pos);
  const lineStart = before.lastIndexOf('\n') + 1;
  const inserted = `@${name} `;
  content.value = content.value.slice(0, lineStart) + inserted + content.value.slice(pos);
  attrMenuVisible.value = false;
  nextTick(() => {
    const caret = lineStart + inserted.length;
    el.setSelectionRange(caret, caret);
    el.focus();
  });
}

function handleEditorKeydown(e: KeyboardEvent) {
  if (attrMenuVisible.value) {
    if (e.key === 'Escape') {
      e.preventDefault();
      attrMenuVisible.value = false;
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      attrMenuIndex.value = (attrMenuIndex.value + 1) % ATTR_OPTIONS.length;
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      attrMenuIndex.value = (attrMenuIndex.value - 1 + ATTR_OPTIONS.length) % ATTR_OPTIONS.length;
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const option = ATTR_OPTIONS[attrMenuIndex.value];
      if (option) insertAttr(option.name);
    }
    return;
  }
  if (activeFile.value?.fileName === 'categories.json') {
    handleJsonKeydown(e);
  }
}

/** categories.json 回车自动缩进、闭合括号自动回退 */
function handleJsonKeydown(e: KeyboardEvent) {
  const el = editorTextarea;
  if (!el) return;
  const pos = el.selectionStart ?? 0;
  const before = content.value.slice(0, pos);
  const after = content.value.slice(pos);
  const lineStart = before.lastIndexOf('\n') + 1;
  const lineText = before.slice(lineStart);

  if (e.key === 'Enter') {
    e.preventDefault();
    const indentMatch = lineText.match(/^\s*/);
    let indent = indentMatch ? indentMatch[0] : '';
    // 上一行以 { 或 [ 结尾，新行多缩进一层
    if (/[{[]\s*$/.test(lineText)) indent += '  ';
    const insert = `\n${indent}`;
    content.value = before + insert + after;
    nextTick(() => el.setSelectionRange(pos + insert.length, pos + insert.length));
    return;
  }
  if (e.key === '}' || e.key === ']') {
    // 当前行只有空白时，闭合括号回退一层缩进
    if (/^\s*$/.test(lineText)) {
      e.preventDefault();
      const newIndent = lineText.length >= 2 ? lineText.slice(2) : '';
      const insert = newIndent + e.key;
      content.value = before.slice(0, lineStart) + insert + after;
      nextTick(() => el.setSelectionRange(lineStart + insert.length, lineStart + insert.length));
    }
  }
}

async function refreshFiles() {
  if (!activeService.value) return;
  files.value = await getConfigFiles(activeService.value.serviceCode);
  const current = files.value.find((item) => item.fileName === activeFile.value?.fileName);
  if (current) activeFile.value = current;
}

function reset() {
  content.value = savedContent.value;
  errorText.value = '';
}

async function save() {
  if (!activeService.value || !activeFile.value) return;
  saving.value = true;
  errorText.value = '';
  try {
    await saveConfigFile({
      serviceCode: activeService.value.serviceCode,
      fileName: activeFile.value.fileName,
      content: content.value,
    });
    savedContent.value = content.value;
    message.success('已保存，立即生效');
    await refreshFiles();
  } catch (error) {
    errorText.value = error instanceof Error && error.message ? error.message : '保存失败，请稍后重试';
  } finally {
    saving.value = false;
  }
}

async function saveInExpand() {
  await save();
  if (!errorText.value) {
    expandVisible.value = false;
  }
}

async function restoreDefault() {
  if (!activeService.value || !activeFile.value) return;
  try {
    await resetConfigFile(activeService.value.serviceCode, activeFile.value.fileName);
    message.success('已恢复默认内容');
    await selectFile(activeFile.value);
    await refreshFiles();
  } catch {
    // 失败提示由全局拦截器统一弹出
  }
}

onMounted(() => void loadServices());
</script>

<template>
  <div class="service-config">
    <div class="page-head">
      <h2>服务配置</h2>
      <span class="page-hint">保存后立即生效</span>
    </div>

    <a-spin :spinning="loading">
      <div class="service-grid">
        <div
          v-for="service in services"
          :key="service.serviceCode"
          class="service-card"
          :class="{ active: service.serviceCode === activeService?.serviceCode, disabled: !service.enabled }"
          @click="selectService(service)"
        >
          <div class="service-name">{{ service.serviceName }}</div>
          <div class="service-sub">{{ service.entryDesc }}</div>
          <div class="service-count">{{ service.enabled ? `${service.fileCount} 个配置文件` : '即将上线' }}</div>
        </div>
      </div>

      <a-empty v-if="!activeService" description="暂无可配置服务" />

      <div v-else class="editor">
        <aside class="file-list">
          <div class="file-list-head">配置文件 ({{ files.length }})</div>
          <div
            v-for="file in files"
            :key="file.fileName"
            class="file-item"
            :class="{ active: file.fileName === activeFile?.fileName }"
            @click="selectFile(file)"
          >
            <div class="file-name">{{ file.fileName }}</div>
            <div class="file-meta">
              <a-tag :color="TAG_COLORS[file.tag] ?? 'default'">{{ TAG_LABELS[file.tag] ?? file.tag }}</a-tag>
              <span class="file-time">{{ file.updateTime ? dayjs(file.updateTime).format('YYYY-MM-DD HH:mm') : '' }}</span>
            </div>
          </div>
        </aside>

        <section class="editor-main">
          <div class="editor-head">
            <div class="editor-title">
              <div class="fname">{{ activeFile?.fileName }}</div>
              <div class="fdesc">{{ activeFile?.description }}</div>
            </div>
            <a-space align="end">
              <a class="rule-link" @click="ruleVisible = true">修改规则</a>
              <a-button @click="expandVisible = true">放大编辑</a-button>
              <a-button @click="restoreDefault">恢复默认</a-button>
              <a-button :disabled="!dirty" @click="reset">重置</a-button>
              <a-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</a-button>
            </a-space>
          </div>

          <a-alert
            v-if="errorText"
            class="error-alert"
            type="error"
            show-icon
            closable
            :message="errorText"
            @close="errorText = ''"
          />

          <div class="editor-wrap">
            <a-textarea
              v-model:value="content"
              class="editor-box"
              spellcheck="false"
              @input="handleEditorInput($event, 'main')"
              @keydown="handleEditorKeydown"
              @scroll="attrMenuVisible = false"
            />
            <div
              v-if="attrMenuVisible && attrMenuTarget === 'main'"
              class="attr-menu"
              :style="{ top: `${attrMenuTop}px` }"
            >
              <div
                v-for="(opt, index) in ATTR_OPTIONS"
                :key="opt.name"
                class="attr-item"
                :class="{ active: index === attrMenuIndex }"
                @mousedown.prevent="insertAttr(opt.name)"
                @mouseenter="attrMenuIndex = index"
              >
                <span class="attr-name">@{{ opt.name }}</span>
                <span class="attr-desc">{{ opt.desc }}</span>
              </div>
            </div>
          </div>
          <div v-if="jsonError" class="json-hint">{{ jsonError }}</div>
          <div class="editor-foot">保存后立即对「{{ activeService?.serviceName }}」生效。</div>
        </section>
      </div>
    </a-spin>

    <a-modal
      v-model:open="expandVisible"
      :title="`编辑 · ${activeFile?.fileName ?? ''}`"
      width="90vw"
      :footer="null"
      destroy-on-close
    >
      <a-alert
        v-if="errorText"
        class="error-alert"
        type="error"
        show-icon
        closable
        :message="errorText"
        @close="errorText = ''"
      />
      <div class="editor-wrap">
        <a-textarea
          v-model:value="content"
          class="expand-editor"
          spellcheck="false"
          @input="handleEditorInput($event, 'expand')"
          @keydown="handleEditorKeydown"
          @scroll="attrMenuVisible = false"
        />
        <div
          v-if="attrMenuVisible && attrMenuTarget === 'expand'"
          class="attr-menu"
          :style="{ top: `${attrMenuTop}px` }"
        >
          <div
            v-for="(opt, index) in ATTR_OPTIONS"
            :key="opt.name"
            class="attr-item"
            :class="{ active: index === attrMenuIndex }"
            @mousedown.prevent="insertAttr(opt.name)"
            @mouseenter="attrMenuIndex = index"
          >
            <span class="attr-name">@{{ opt.name }}</span>
            <span class="attr-desc">{{ opt.desc }}</span>
          </div>
        </div>
      </div>
      <div v-if="jsonError" class="json-hint">{{ jsonError }}</div>
      <div class="expand-actions">
        <a-space>
          <a-button @click="expandVisible = false">关闭</a-button>
          <a-button type="primary" :loading="saving" :disabled="!dirty" @click="saveInExpand">保存</a-button>
        </a-space>
      </div>
    </a-modal>

    <a-modal v-model:open="ruleVisible" title="修改规则" :footer="null" width="680px">
      <pre class="rule-text">{{ ruleText }}</pre>
    </a-modal>
  </div>
</template>

<style scoped>
.service-config { padding: 20px 24px 24px; }
.page-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; font-weight: 600; color: var(--color-text-primary); }
.page-hint { color: var(--color-text-muted); font-size: 12px; }

.service-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 8px; }
.service-card { padding: 16px 18px; border: 1px solid var(--color-border, #e8e8ed); border-radius: 8px; cursor: pointer; transition: border-color 0.15s, background-color 0.15s; }
.service-card:hover { border-color: #d9d9d9; }
.service-card.active { border-color: var(--color-primary); background: var(--color-primary-bg, #f0f0ff); }
.service-card.disabled { cursor: not-allowed; opacity: 0.6; }
.service-name { font-size: 14px; font-weight: 500; color: var(--color-text-primary); }
.service-card.active .service-name { color: var(--color-primary); }
.service-sub { margin-top: 4px; color: var(--color-text-secondary); font-size: 12px; }
.service-count { margin-top: 6px; color: var(--color-text-muted); font-size: 12px; }

.editor { display: grid; grid-template-columns: 216px 1fr; gap: 0; margin-top: 20px; border-top: 1px solid var(--color-border, #e8e8ed); padding-top: 20px; }
.file-list { border-right: 1px solid var(--color-border, #e8e8ed); padding-right: 16px; }
.file-list-head { color: var(--color-text-muted); font-size: 12px; padding-bottom: 8px; }
.file-item { padding: 8px; border-radius: 6px; cursor: pointer; margin-bottom: 2px; }
.file-item:hover { background: var(--color-surface-alt, #fafafa); }
.file-item.active { background: var(--color-primary-bg, #f0f0ff); }
.file-name { font-size: 13px; color: var(--color-text-secondary); word-break: break-all; }
.file-item.active .file-name { color: var(--color-primary); font-weight: 500; }
.file-meta { display: flex; align-items: center; gap: 6px; margin-top: 4px; flex-wrap: wrap; }
.file-time { color: var(--color-text-muted); font-size: 12px; }

.editor-main { min-width: 0; padding-left: 24px; }
.editor-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 10px; }
.editor-title { flex: 1; min-width: 0; }
.fname { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; font-weight: 500; color: var(--color-text-primary); }
.fdesc { margin-top: 4px; color: var(--color-text-secondary); font-size: 12px; }
.error-alert { margin-bottom: 10px; white-space: pre-wrap; }
.editor-box { height: calc(100vh - 400px); min-height: 340px; font-size: 14px; line-height: 1.75; resize: none; }
.editor-wrap { position: relative; }
.attr-menu { position: absolute; left: 8px; z-index: 20; width: 280px; max-height: 260px; overflow-y: auto; padding: 4px; border: 1px solid var(--color-border, #e8e8ed); border-radius: 6px; background: var(--color-surface, #fff); box-shadow: 0 4px 12px rgb(0 0 0 / 12%); }
.attr-item { display: flex; align-items: baseline; gap: 8px; padding: 6px 8px; border-radius: 4px; cursor: pointer; }
.attr-item:hover { background: var(--color-primary-bg, #f0f0ff); }
.attr-item.active { background: var(--color-primary-bg, #f0f0ff); outline: 1px solid var(--color-primary); outline-offset: -1px; }
.attr-name { flex: 0 0 auto; color: var(--color-primary); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; }
.attr-desc { color: var(--color-text-muted); font-size: 12px; }
.editor-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; color: var(--color-text-muted); font-size: 12px; }
.json-hint { margin-top: 8px; color: var(--color-error, #e5484d); font-size: 12px; }
.expand-editor { height: calc(90vh - 180px); font-size: 14px; line-height: 1.75; }
.expand-actions { margin-top: 12px; text-align: right; }
.rule-text { margin: 0; white-space: pre-wrap; font-size: 13px; line-height: 1.85; color: var(--color-text-secondary); }
.rule-link { color: var(--color-primary); font-size: 12px; text-decoration: underline; cursor: pointer; white-space: nowrap; }
.rule-link:hover { color: var(--color-primary-hover, #4a4ac4); }

@media (max-width: 900px) {
  .service-grid { grid-template-columns: 1fr; }
  .editor { grid-template-columns: 1fr; }
  .file-list { border-right: none; border-bottom: 1px solid var(--color-border, #e8e8ed); padding: 0 0 12px; margin-bottom: 16px; }
  .editor-main { padding-left: 0; }
}
</style>
