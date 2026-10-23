<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { Card, Table, Button, Space, Input, Select, Tag, Drawer, Timeline, Empty, Modal, Tabs, message } from 'ant-design-vue';
import { ReloadOutlined, SearchOutlined, RobotOutlined, PlusOutlined } from '@ant-design/icons-vue';
import {
  getXianyuMachineAgentPage,
  getXianyuMachineAgentAccounts,
  getXianyuMachineAgentEvents,
  resetXianyuMachineAgentToken,
  createXianyuAgentEnrollCode,
  getXianyuAgentEnrollPage,
  revokeXianyuAgentEnrollCode,
} from '#/api/app/xianyu/agent';
import type { XianyuMachineAgentApi } from '#/api/app/xianyu/agent';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'AppXianyuAgent' });

const activeTab = ref<'machines' | 'enroll'>('machines');

// ====== 主表格 ======
const loading = ref(false);
const dataSource = ref<XianyuMachineAgentApi.MachineAgent[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref<XianyuMachineAgentApi.AgentPageReqVO>({ pageNo: 1, pageSize: 20 });

const columns = [
  { title: 'Agent 名称', dataIndex: 'name', width: 160 },
  { title: '主机名', dataIndex: 'hostName', width: 140 },
  { title: '状态', dataIndex: 'status', width: 90 },
  { title: '锁屏', dataIndex: 'screenLocked', width: 90 },
  { title: '账号数', dataIndex: 'accountCount', width: 80, align: 'center' as const },
  { title: 'Token', dataIndex: 'tokenActivated', width: 90 },
  { title: '版本', dataIndex: 'agentVersion', width: 100 },
  { title: '最后心跳', dataIndex: 'lastHeartbeatAt', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '备注', dataIndex: 'remark', ellipsis: true },
  { title: '操作', key: 'operation', width: 150, fixed: 'right' as const },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getXianyuMachineAgentPage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.pageNo = 1;
  loadData();
}

function handleReset() {
  searchForm.value = { pageNo: 1, pageSize: 20 };
  handleSearch();
}

function handleTableChange(p: { current?: number; pageSize?: number }) {
  pagination.value.pageNo = p.current || 1;
  pagination.value.pageSize = p.pageSize || 20;
  loadData();
}

// 心跳时间 1 分钟内刷新（01 §8.2 验收项 1），每 30s 自动刷新
let refreshTimer: ReturnType<typeof setInterval> | undefined;
function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(() => {
    // 详情抽屉打开时不刷新列表，避免表格跳动；注册码页无实时性要求，也不刷新
    if (!detailVisible.value && activeTab.value === 'machines') loadData();
  }, 30_000);
}
function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = undefined;
  }
}

// ====== 详情抽屉 ======
const detailVisible = ref(false);
const detailAgent = ref<XianyuMachineAgentApi.MachineAgent>();
const accountsLoading = ref(false);
const accounts = ref<XianyuMachineAgentApi.AgentAccount[]>([]);
const eventsLoading = ref(false);
const events = ref<XianyuMachineAgentApi.AgentEvent[]>([]);
const eventsTotal = ref(0);
const eventsPagination = ref({ pageNo: 1, pageSize: 10 });
const eventsLevelFilter = ref<string | undefined>();

const EVENT_TYPE_TEXT: Record<string, string> = {
  agent_start: 'Agent 启动',
  agent_stop: 'Agent 停止',
  login_expired: '闲管家登录失效',
  screen_locked: '屏幕锁定',
  ocr_failed: 'OCR 识别失败',
  send_success: '回复发送成功',
  send_failed: '回复发送失败',
  send_unknown: '发送结果未知',
  client_error: '客户端异常',
};

const LOGIN_STATUS_TEXT: Record<string, string> = {
  online: '在线',
  offline: '离线',
  unknown: '未知',
};

const accountColumns = [
  { title: '昵称', dataIndex: 'userNick', width: 140 },
  { title: '登录状态', dataIndex: 'loginStatus', width: 90 },
  { title: '最近上报', dataIndex: 'lastReportAt', width: 170, customRender: ({ text }: { text: string }) => formatDateTime(text) },
];

function openDetail(record: XianyuMachineAgentApi.MachineAgent) {
  detailAgent.value = record;
  detailVisible.value = true;
  loadAccounts(record.id!);
  loadEvents(record.id!);
}

async function loadAccounts(agentId: number) {
  accountsLoading.value = true;
  try {
    accounts.value = await getXianyuMachineAgentAccounts(agentId);
  } catch {
    accounts.value = [];
  } finally {
    accountsLoading.value = false;
  }
}

async function loadEvents(agentId: number) {
  eventsLoading.value = true;
  try {
    const res = await getXianyuMachineAgentEvents(agentId, {
      ...eventsPagination.value,
      level: eventsLevelFilter.value,
    });
    events.value = res.list || [];
    eventsTotal.value = res.total || 0;
  } catch {
    events.value = [];
    eventsTotal.value = 0;
  } finally {
    eventsLoading.value = false;
  }
}

function handleEventsLevelChange() {
  eventsPagination.value.pageNo = 1;
  loadEvents(detailAgent.value!.id!);
}

function prevEventsPage() {
  eventsPagination.value.pageNo -= 1;
  loadEvents(detailAgent.value!.id!);
}

function nextEventsPage() {
  eventsPagination.value.pageNo += 1;
  loadEvents(detailAgent.value!.id!);
}

// ====== 一次性注册码（新机器接入） ======
const ENROLL_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待使用', color: 'green' },
  1: { text: '已使用', color: 'default' },
  2: { text: '已过期', color: 'red' },
};

const enrollLoading = ref(false);
const enrollData = ref<XianyuMachineAgentApi.AgentEnroll[]>([]);
const enrollTotal = ref(0);
const enrollPagination = ref({ pageNo: 1, pageSize: 20 });
const enrollSearch = ref<{ remark?: string; status?: number }>({});
let enrollLoaded = false;

const enrollColumns = [
  { title: '备注', dataIndex: 'remark', width: 180, ellipsis: true },
  { title: '状态', dataIndex: 'status', width: 90 },
  { title: '过期时间', dataIndex: 'expireTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '使用时间', dataIndex: 'usedTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '注册的机器', dataIndex: 'agentName', width: 160 },
  { title: '生成时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 90, fixed: 'right' as const },
];

async function loadEnrollData() {
  enrollLoading.value = true;
  try {
    const res = await getXianyuAgentEnrollPage({ ...enrollPagination.value, ...enrollSearch.value });
    enrollData.value = res.list || [];
    enrollTotal.value = res.total || 0;
  } finally {
    enrollLoading.value = false;
  }
}

function handleEnrollSearch() {
  enrollPagination.value.pageNo = 1;
  loadEnrollData();
}

function handleEnrollReset() {
  enrollSearch.value = {};
  handleEnrollSearch();
}

function handleEnrollTableChange(p: { current?: number; pageSize?: number }) {
  enrollPagination.value.pageNo = p.current || 1;
  enrollPagination.value.pageSize = p.pageSize || 20;
  loadEnrollData();
}

const enrollFormVisible = ref(false);
const enrollCreating = ref(false);
const enrollRemark = ref('');

const enrollCodeVisible = ref(false);
const createdEnroll = ref<XianyuMachineAgentApi.AgentEnrollCreated>({});

function openEnrollForm() {
  enrollRemark.value = '';
  enrollFormVisible.value = true;
}

async function handleCreateEnroll() {
  enrollCreating.value = true;
  try {
    createdEnroll.value = await createXianyuAgentEnrollCode({ remark: enrollRemark.value.trim() || undefined });
    enrollFormVisible.value = false;
    enrollCodeVisible.value = true;
    loadEnrollData();
  } finally {
    enrollCreating.value = false;
  }
}

function handleRevokeEnroll(record: XianyuMachineAgentApi.AgentEnroll) {
  Modal.confirm({
    title: '撤销注册码',
    content: `撤销后「${record.remark || `注册码 #${record.id}`}」立即失效，持该码的机器将无法完成注册。确认撤销？`,
    okText: '撤销',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await revokeXianyuAgentEnrollCode(record.id!);
      message.success('已撤销');
      loadEnrollData();
    },
  });
}

onMounted(() => {
  loadData();
  startAutoRefresh();
});

onUnmounted(stopAutoRefresh);

// 注册码列表首次进入该 Tab 时才拉取
watch(activeTab, (tab) => {
  if (tab === 'enroll' && !enrollLoaded) {
    enrollLoaded = true;
    loadEnrollData();
  }
});

// ====== 重置专属 Token（M5 一机一 Token） ======
const tokenModalVisible = ref(false);
const newToken = ref('');

function handleResetToken(record: XianyuMachineAgentApi.MachineAgent) {
  Modal.confirm({
    title: '重置专属 Token',
    content: `重置后旧 Token 立即失效，「${record.name || record.hostName || '该 Agent'}」的下一次请求将被拒绝（401）。` +
      '新 Token 仅显示一次，需复制并写入该机器工作目录的 .agent_token 文件后重启 Agent。确认重置？',
    okText: '重置',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        newToken.value = await resetXianyuMachineAgentToken(record.id!);
        tokenModalVisible.value = true;
        loadData();
      } catch (e) {
        // 错误信息由请求封装统一提示
      }
    },
  });
}

function copyText(text: string): Promise<void> {
  if (navigator.clipboard) {
    return navigator.clipboard.writeText(text);
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand('copy');
  document.body.removeChild(textarea);
  return Promise.resolve();
}

async function handleCopyToken() {
  try {
    await copyText(newToken.value);
    message.success('Token 已复制');
  } catch {
    message.warning('复制失败，请手动选择复制');
  }
}

async function handleCopyEnrollCode() {
  try {
    await copyText(createdEnroll.value.code || '');
    message.success('注册码已复制');
  } catch {
    message.warning('复制失败，请手动选择复制');
  }
}
</script>

<template>
  <Card title="Agent 管理" :bordered="false">
    <Tabs v-model:activeKey="activeTab">
      <Tabs.TabPane key="machines" tab="机器列表">
        <!-- 搜索区域 -->
        <div class="search-area">
          <Space wrap size="middle">
            <Input
              v-model:value="searchForm.name"
              placeholder="Agent 名称"
              allow-clear
              style="width: 180px"
              @press-enter="handleSearch"
            />
            <Select
              v-model:value="searchForm.status"
              placeholder="状态"
              allow-clear
              style="width: 130px"
            >
              <Select.Option value="online">在线</Select.Option>
              <Select.Option value="offline">离线</Select.Option>
            </Select>
            <Button type="primary" @click="handleSearch"><SearchOutlined /> 查询</Button>
            <Button @click="handleReset">重置</Button>
            <Button @click="loadData"><ReloadOutlined /> 刷新</Button>
          </Space>
        </div>

        <Table
          :columns="columns"
          :data-source="dataSource"
          :loading="loading"
          :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showSizeChanger: true }"
          row-key="id"
          @change="handleTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'name'">
              <Space size="small">
                <RobotOutlined style="color: var(--color-primary)" />
                <span>{{ record.name || '-' }}</span>
              </Space>
            </template>
            <template v-if="column.dataIndex === 'status'">
              <Tag :color="record.status === 'online' ? 'green' : 'default'">
                {{ record.status === 'online' ? '在线' : '离线' }}
              </Tag>
            </template>
            <template v-if="column.dataIndex === 'screenLocked'">
              <Tag v-if="record.screenLocked" color="red">锁屏中</Tag>
              <span v-else class="muted">正常</span>
            </template>
            <template v-if="column.dataIndex === 'accountCount'">
              {{ record.accountCount ?? 0 }}
            </template>
            <template v-if="column.dataIndex === 'tokenActivated'">
              <Tag v-if="record.tokenActivated" color="green">已激活</Tag>
              <Tag v-else color="orange">未激活</Tag>
            </template>
            <template v-if="column.dataIndex === 'remark'">
              {{ record.remark || '-' }}
            </template>
            <template v-if="column.key === 'operation'">
              <Space size="small">
                <a @click="openDetail(record as XianyuMachineAgentApi.MachineAgent)">详情</a>
                <a @click="handleResetToken(record as XianyuMachineAgentApi.MachineAgent)">重置Token</a>
              </Space>
            </template>
          </template>
        </Table>

        <Empty v-if="!loading && !dataSource.length" description="暂无 Agent 接入，请到「注册码」页生成注册码后在新机器启动 Agent" />
      </Tabs.TabPane>

      <Tabs.TabPane key="enroll" tab="注册码">
        <div class="enroll-tip">
          新机器接入流程：生成注册码 → 复制明文 → 填入该机器的
          <code>config.local.yaml</code> 的 <code>server.enroll_code</code> → 启动 Agent。
          Agent 首次心跳会用注册码换回「专属 Token」并落盘到 <code>.agent_token</code>，此后注册码即作废。
          注册码短时效、单次消费，明文只展示一次。
        </div>

        <div class="search-area">
          <Space wrap size="middle">
            <Input
              v-model:value="enrollSearch.remark"
              placeholder="备注（机器名）"
              allow-clear
              style="width: 180px"
              @press-enter="handleEnrollSearch"
            />
            <Select
              v-model:value="enrollSearch.status"
              placeholder="状态"
              allow-clear
              style="width: 130px"
            >
              <Select.Option :value="0">待使用</Select.Option>
              <Select.Option :value="1">已使用</Select.Option>
              <Select.Option :value="2">已过期</Select.Option>
            </Select>
            <Button type="primary" @click="handleEnrollSearch"><SearchOutlined /> 查询</Button>
            <Button @click="handleEnrollReset">重置</Button>
            <Button type="primary" ghost @click="openEnrollForm"><PlusOutlined /> 生成注册码</Button>
          </Space>
        </div>

        <Table
          :columns="enrollColumns"
          :data-source="enrollData"
          :loading="enrollLoading"
          :pagination="{ current: enrollPagination.pageNo, pageSize: enrollPagination.pageSize, total: enrollTotal, showSizeChanger: true }"
          row-key="id"
          @change="handleEnrollTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'remark'">
              {{ record.remark || '-' }}
            </template>
            <template v-if="column.dataIndex === 'status'">
              <Tag :color="ENROLL_STATUS[record.status as number]?.color || 'default'">
                {{ ENROLL_STATUS[record.status as number]?.text || '-' }}
              </Tag>
            </template>
            <template v-if="column.dataIndex === 'agentName'">
              {{ record.agentName || '-' }}
            </template>
            <template v-if="column.key === 'operation'">
              <a v-if="record.status === 0" class="danger-link" @click="handleRevokeEnroll(record as XianyuMachineAgentApi.AgentEnroll)">撤销</a>
              <span v-else class="muted">-</span>
            </template>
          </template>
        </Table>

        <Empty v-if="!enrollLoading && !enrollData.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无注册码" />
      </Tabs.TabPane>
    </Tabs>
  </Card>

  <!-- Agent 详情抽屉 -->
  <Drawer
    v-model:open="detailVisible"
    :title="detailAgent?.name || 'Agent 详情'"
    width="720px"
  >
    <template v-if="detailAgent">
      <div class="agent-meta">
        <span class="meta-item">主机名：{{ detailAgent.hostName || '-' }}</span>
        <span class="meta-item">版本：{{ detailAgent.agentVersion || '-' }}</span>
        <span class="meta-item">最后心跳：{{ formatDateTime(detailAgent.lastHeartbeatAt) }}</span>
      </div>

      <h4 class="section-title">闲鱼号列表（{{ accounts.length }}）</h4>
      <Table
        :columns="accountColumns"
        :data-source="accounts"
        :loading="accountsLoading"
        :pagination="false"
        size="small"
        row-key="userNick"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'loginStatus'">
            <Tag :color="record.loginStatus === 'online' ? 'green' : record.loginStatus === 'offline' ? 'red' : 'default'">
              {{ LOGIN_STATUS_TEXT[record.loginStatus] || record.loginStatus || '-' }}
            </Tag>
          </template>
        </template>
        <template #emptyText>
          <Empty :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无账号上报（等待 Agent 心跳携带账号列表）" />
        </template>
      </Table>

      <div class="events-header">
        <h4 class="section-title">事件流</h4>
        <Select
          v-model:value="eventsLevelFilter"
          placeholder="级别筛选"
          allow-clear
          size="small"
          style="width: 120px"
          @change="handleEventsLevelChange"
        >
          <Select.Option value="info">info</Select.Option>
          <Select.Option value="warn">warn</Select.Option>
          <Select.Option value="error">error</Select.Option>
        </Select>
      </div>
      <Timeline v-if="events.length" :pending="eventsLoading ? '加载中…' : undefined">
        <Timeline.Item
          v-for="ev in events"
          :key="ev.id"
          :color="ev.level === 'error' ? 'red' : ev.level === 'warn' ? 'orange' : 'blue'"
        >
          <div class="event-row">
            <span class="event-time">{{ formatDateTime(ev.createTime) }}</span>
            <Tag v-if="ev.level" :color="ev.level === 'error' ? 'red' : ev.level === 'warn' ? 'orange' : 'blue'" class="event-tag">
              {{ ev.level }}
            </Tag>
            <span class="event-type">{{ EVENT_TYPE_TEXT[ev.eventType || ''] || ev.eventType }}</span>
          </div>
          <div class="event-content">{{ ev.content }}</div>
        </Timeline.Item>
      </Timeline>
      <Empty v-else-if="!eventsLoading" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无事件" />
      <div v-if="eventsTotal > eventsPagination.pageSize" class="events-pager">
        <Button
          size="small"
          :disabled="eventsPagination.pageNo <= 1"
          @click="prevEventsPage"
        >上一页</Button>
        <span class="muted">{{ eventsPagination.pageNo }} / {{ Math.ceil(eventsTotal / eventsPagination.pageSize) }}</span>
        <Button
          size="small"
          :disabled="eventsPagination.pageNo >= Math.ceil(eventsTotal / eventsPagination.pageSize)"
          @click="nextEventsPage"
        >下一页</Button>
      </div>
    </template>
  </Drawer>

  <!-- 新专属 Token 展示（仅显示一次） -->
  <Modal
    v-model:open="tokenModalVisible"
    title="新专属 Token（仅显示一次）"
    :footer="null"
    :mask-closable="false"
    width="560px"
  >
    <p class="token-tip">
      请立即复制并写入该 Agent 机器工作目录的 <code>.agent_token</code> 文件，然后重启 Agent。
      关闭本窗口后将无法再次查看；旧 Token 已失效。
    </p>
    <Input.TextArea :value="newToken" :rows="2" readonly class="token-textarea" />
    <div class="token-actions">
      <Button type="primary" @click="handleCopyToken">复制 Token</Button>
      <Button @click="tokenModalVisible = false">我已保存</Button>
    </div>
  </Modal>

  <!-- 生成注册码：填写备注 -->
  <Modal
    v-model:open="enrollFormVisible"
    title="生成一次性注册码"
    :confirm-loading="enrollCreating"
    :mask-closable="false"
    ok-text="生成"
    cancel-text="取消"
    width="480px"
    @ok="handleCreateEnroll"
  >
    <p class="token-tip">
      注册码短时效（服务端 <code>enroll-code-ttl-minutes</code>，默认 15 分钟）、一次性消费，
      只用于新机器换取专属 Token。备注建议填目标机器名，便于在列表里认出这张码发给谁。
    </p>
    <Input
      v-model:value="enrollRemark"
      placeholder="备注，如 xianguanjia-02"
      :maxlength="128"
      allow-clear
    />
  </Modal>

  <!-- 注册码明文展示（仅显示一次） -->
  <Modal
    v-model:open="enrollCodeVisible"
    title="注册码（仅显示一次）"
    :footer="null"
    :mask-closable="false"
    width="560px"
  >
    <p class="token-tip">
      请立即复制。关闭本窗口后无法再次查看（库中只存哈希）。<br />
      过期时间：{{ formatDateTime(createdEnroll.expireTime) }}，超时或注册成功一次后即失效。
    </p>
    <Input.TextArea :value="createdEnroll.code" :rows="2" readonly class="token-textarea" />
    <p class="token-tip enroll-usage">
      用法：填入该机器 <code>config.local.yaml</code> 的 <code>server.enroll_code</code>；
      走 VM 部署脚本时也可写入仓库 <code>temp/enroll_code.txt</code> 后执行
      <code>qga_deploy.py write-config</code>。Agent 首次心跳成功后会自动领取专属 Token 并落盘
      <code>.agent_token</code>，之后可把注册码从配置里删掉。
    </p>
    <div class="token-actions">
      <Button type="primary" @click="handleCopyEnrollCode">复制注册码</Button>
      <Button @click="enrollCodeVisible = false">我已保存</Button>
    </div>
  </Modal>
</template>

<style scoped>
.search-area {
  background: var(--color-bg-layout, #f5f5f5);
  border-radius: var(--radius-md, 8px);
  padding: 12px;
  margin-bottom: 16px;
}

.muted {
  color: var(--color-text-muted);
}

.danger-link {
  color: var(--color-error, #ff4d4f);
}

.enroll-tip {
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.7;
  margin-bottom: 12px;
}

.enroll-tip code,
.token-tip code {
  background: var(--color-bg-layout, #f5f5f5);
  border-radius: var(--radius-sm, 4px);
  padding: 1px 4px;
  font-family: 'Courier New', monospace;
}

.agent-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--color-text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
}

.section-title {
  margin: 8px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.events-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24px;
}

.events-header .section-title {
  margin: 0;
}

.event-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.event-time {
  color: var(--color-text-muted);
  font-size: 12px;
}

.event-tag {
  font-size: 11px;
}

.event-type {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.event-content {
  margin-top: 4px;
  color: var(--color-text-primary);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}

.events-pager {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  margin-top: 12px;
}

.token-tip {
  margin-bottom: 12px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.enroll-usage {
  margin-top: 12px;
}

.token-textarea {
  font-family: 'Courier New', monospace;
  word-break: break-all;
}

.token-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
