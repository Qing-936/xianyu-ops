import type { AxiosResponse } from 'axios';
import type { PageParam, PageResult } from '#/utils/request';
import { requestClient } from '#/utils/request';

/** 创建任务请求 */
export interface SourceTaskCreateReqVO {
  productName: string;
  filterMinPrice?: number;
  filterMaxPrice?: number;
  filterViewCountMin?: number;
  filterViewCountMax?: number;
  filterWantCountMin?: number;
  filterWantCountMax?: number;
  fetchCount: number;
  triggerMode: number;
  scheduleTime?: string;
}

/** 任务分页请求 */
export interface SourceTaskPageReqVO {
  pageNo: number;
  pageSize: number;
  productName?: string;
  status?: number;
  triggerMode?: number;
}

/** 任务响应 */
export interface SourceTaskRespVO {
  id: number;
  productName: string;
  filterMinPrice?: number;
  filterMaxPrice?: number;
  fetchCount: number;
  executedCount: number;
  status: number;
  errorMessage?: string;
  triggerMode: number;
  scheduleTime?: string;
  createTime: string;
  retryable?: boolean;
  latestExecutionEmpty?: boolean;
}

/** 尚无任何商品入库的货源任务汇总（只看有数据的最新批次，该批次所有采集商品均未入库） */
export interface SourceTaskPendingInventoryRespVO {
  taskId: number;
  taskName: string;
  pendingResultCount: number;
  latestExecutionNo: number;
  latestExecutionTime: string;
  lastViewedExecutionNo: number | null;
  totalPendingResultCount: number;
}

export interface SourceCookieRefreshRespVO {
  sessionId: string;
  status: string;
  qrUrl: string;
  expireSeconds: number;
  message?: string;
}

export interface SourceCookieRefreshStatusRespVO {
  sessionId: string;
  status: string;
  message: string;
}

/** 批次汇总响应 */
export interface SourceExecutionRespVO {
  executionNo: number;
  keyword: string;
  fetchCount: number;
  validCount: number;
  minPrice?: number;
  maxPrice?: number;
  avgPrice?: number;
  priceIndex?: number;
  executedAt: string;
  status: number;
}

/** 搜索结果响应 */
export interface SourceItemRespVO {
  id: number;
  title: string;
  sellerName?: string;
  price: number;
  createTime: string;
  detailUrl?: string;
  viewCount?: number;
  wantCount?: number;
  heatLevel?: string;
  highValue?: string;
  competitiveness?: string;
  sellerLevel?: string;
  searchKeyword?: string;
  detailId?: number;
  sourceInventoryId?: number;
}


/** 货源库分页请求 */
export interface SourceInventoryPageReqVO extends PageParam {
  searchKeyword?: string;
  title?: string;
  /** 页面状态下拉选项（1已入库/2已创建/6清洗中/7已清洗/8清洗失败） */
  displayStatus?: number;
  /** 入库状态筛选 */
  inventoryStatus?: number;
  inventoryTimeStart?: string;
  inventoryTimeEnd?: string;
}

/** 货源库记录 */
export interface SourceInventoryRespVO {
  id: number;
  title: string;
  description?: string;
  searchKeyword?: string;
  price?: number;
  originalPrice?: number;
  itemCondition?: string;
  brand?: string;
  picUrl?: string;
  images?: string;
  detailUrl?: string;
  viewCount?: number;
  wantCount?: number;
  sellerName?: string;
  sellerCity?: string;
  inventoryStatus: number;
  cleanStatus?: number;
  /** clean_html 是否非空（决定能否进行图片微调） */
  hasCleanHtml?: boolean;
  inventoryTime?: string;
  xianyuProductId?: number;
}

export interface SourceInventoryManualImportReqVO {
  detailUrls: string[];
}

export interface SourceInventoryManualImportItemRespVO {
  detailUrl: string;
  success: boolean;
  message: string;
}

export interface SourceInventoryManualImportRespVO {
  totalCount: number;
  successCount: number;
  failedCount: number;
  results: SourceInventoryManualImportItemRespVO[];
}

/** 货源库批量导入 - 解析预览项 */
export interface SourceInventoryImportParseItem {
  rowNo?: number;
  platform?: string;
  title: string;
  priceText?: string;
  price?: number;
  link: string;
  externalItemId?: string;
  images?: string[];
  seller?: string;
  itemCondition?: string;
  functionStatus?: string;
  duplicated?: boolean;
  warnings?: string[];
}

/** 货源库批量导入 - 单条商品 */
export interface SourceInventoryImportItem {
  rowNo?: number;
  title: string;
  link: string;
  externalItemId?: string;
  price?: number;
  images?: string[];
  seller?: string;
  itemCondition?: string;
  functionStatus?: string;
}

/** 货源库批量导入 - 创建请求 */
export interface SourceInventoryImportCreateReqVO {
  items: SourceInventoryImportItem[];
}

/** 货源库批量导入 - 单条结果 */
export interface SourceInventoryImportResult {
  rowNo?: number;
  title: string;
  inventoryId?: number;
  success: boolean;
  message?: string;
}

/** AI 清洗分类 */
export interface SourceCleanCategoryRespVO {
  categoryCode: string;
  categoryName: string;
  groupName?: string;
  sortOrder?: number;
}

/** AI 清洗 Skill 选项 */
export interface SourceCleanSkillRespVO {
  skillCode: string;
  /** title / description / images */
  skillType: string;
  skillName: string;
  version: string;
  description?: string;
  categoryScopes?: string[];
  sortOrder?: number;
  supportsReferenceImage?: boolean;
}

export interface SourceInventoryCleanRecommendReqVO {
  inventoryIds: number[];
}

export interface SourceCleanRecommendationChoice {
  skillCode: string;
  confidence?: number;
  reason?: string;
}

export interface SourceInventoryCleanRecommendItem {
  inventoryId: number;
  title?: string;
  mainImageUrl?: string;
  hasOriginalImage?: boolean;
  routingCategory?: {
    categoryCode: string;
    categoryName: string;
    confidence?: number;
    reason?: string;
  };
  recommendations?: Partial<Record<'title' | 'description' | 'images', SourceCleanRecommendationChoice>>;
  referenceOriginalImageRecommended?: boolean;
  warnings?: string[];
}

export interface SourceInventoryCleanRecommendRespVO {
  recommendationTraceId: string;
  catalogVersion: string;
  items: SourceInventoryCleanRecommendItem[];
  skills: SourceCleanSkillRespVO[];
  categories: SourceCleanCategoryRespVO[];
}

export interface SourceInventoryCleanSubmitItem {
  inventoryId: number;
  categoryCode: string;
  cleanTypes: string[];
  skills: Record<string, string>;
  referenceOriginalImage: boolean;
}

/** AI 清洗任务提交请求 */
export interface SourceInventoryCleanSubmitReqVO {
  clientRequestId: string;
  recommendationTraceId?: string;
  catalogVersion?: string;
  /** 头图补充要求（可选，整批共用） */
  requirementImages?: string;
  /** 标题补充要求（可选，整批共用） */
  requirementTitle?: string;
  /** 描述补充要求（可选，整批共用） */
  requirementDescription?: string;
  items: SourceInventoryCleanSubmitItem[];
}

/** AI 清洗任务提交响应 */
export interface SourceInventoryCleanSubmitRespVO {
  taskId: number;
  totalCount: number;
  message: string;
}

/** AI 清洗任务状态响应 */
export interface SourceInventoryCleanStatusRespVO {
  taskId: number;
  status: number;
  progress: number;
  totalCount: number;
  completedCount: number;
  failedCount: number;
  errorMessage?: string;
  results?: Record<string, { status?: string; error?: string }>;
}

export const submitSourceInventoryManualImport = (data: SourceInventoryManualImportReqVO) =>
  requestClient.post<SourceInventoryManualImportRespVO>('/app/source/inventory/manual-import', data);

/** 重新采集并刷新货源 */
export const refreshSourceInventory = (id: number) =>
  requestClient.post<boolean>('/app/source/inventory/refresh', undefined, { params: { id } });

/** 解析 Excel 生成货源库导入预览（不落库） */
export const parseSourceInventoryImport = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return requestClient.post<SourceInventoryImportParseItem[]>(
    '/app/source/inventory/import/parse',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
};

/** 确认导入到货源库（逐条写入，单条失败不影响其他） */
export const createSourceInventoryImport = (data: SourceInventoryImportCreateReqVO) =>
  requestClient.post<SourceInventoryImportResult[]>('/app/source/inventory/import/create', data);

export const getSourceInventoryPage = (params: SourceInventoryPageReqVO) =>
  requestClient.get<PageResult<SourceInventoryRespVO>>('/app/source/inventory/page', { params });

export const deleteSourceInventory = (id: number) =>
  requestClient.delete<boolean>('/app/source/inventory/delete', { params: { id } });

export const batchDeleteSourceInventory = (ids: number[]) =>
  requestClient.post<boolean>('/app/source/inventory/batch-delete', ids);

export const exportSourceInventoryCsv = (params: Omit<SourceInventoryPageReqVO, 'pageNo' | 'pageSize'>) =>
  requestClient.get<AxiosResponse<Blob>>('/app/source/inventory/export-csv', { params, responseType: 'blob' });
export const createSourceTask = (data: SourceTaskCreateReqVO) =>
  requestClient.post<number>('/app/source/task/create', data);

// 分页查询任务
export const getSourceTaskPage = (params: SourceTaskPageReqVO) =>
  requestClient.get<{ list: SourceTaskRespVO[]; total: number }>('/app/source/task/page', {
    params,
  });

export const getPendingInventoryTaskPage = (params: PageParam) =>
  requestClient.get<PageResult<SourceTaskPendingInventoryRespVO>>(
    '/app/source/task/pending-inventory-page',
    { params },
  );

export const deleteSourceTask = (id: number) =>
  requestClient.delete<boolean>('/app/source/task/delete', { params: { id } });

// 暂停任务
export const pauseSourceTask = (id: number) =>
  requestClient.put<boolean>('/app/source/task/pause', null, { params: { id } });

// 恢复任务
export const resumeSourceTask = (id: number) =>
  requestClient.put<boolean>('/app/source/task/resume', null, { params: { id } });

// 立即执行任务
export const executeSourceTask = (id: number) =>
  requestClient.put<boolean>('/app/source/task/execute', null, { params: { id } });

// 标记任务已查看（记录最后查看批次号）
export const markSourceTaskViewed = (id: number, executionNo: number) =>
  requestClient.put<boolean>('/app/source/task/viewed', null, { params: { id, executionNo } });

// 查询执行批次列表
export const getSourceExecutionList = (taskId: number) =>
  requestClient.get<SourceExecutionRespVO[]>('/app/source/task/execution/list', {
    params: { taskId },
  });

// 查询批次搜索结果
export const getSourceItemList = (taskId: number, executionNo: number) =>
  requestClient.get<SourceItemRespVO[]>('/app/source/task/item/list', {
    params: { taskId, executionNo },
  });

// 写入货源库（同步下载图片、转码 JPEG 并上传 OSS，单件耗时可达数十秒，故放宽读超时）
export const addSourceItemToInventory = (detailId: number) =>
  requestClient.post<boolean>('/app/source/task/item/inventory', null, {
    params: { detailId },
    timeout: 150000,
  });

// 导出CSV（使用下载方式）
export const exportSourceTaskCsv = (taskId: number) =>
  requestClient.get<Blob>('/app/source/task/export-csv', {
    params: { taskId },
    responseType: 'blob',
  });

export const refreshSourceCookieQr = () =>
  requestClient.post<SourceCookieRefreshRespVO>('/app/source/task/cookie/refresh');

export const getSourceCookieRefreshStatus = (sessionId: string) =>
  requestClient.get<SourceCookieRefreshStatusRespVO>('/app/source/task/cookie/refresh/status', {
    params: { sessionId },
  });

/** 提交 AI 清洗任务 */
export const submitCleanTask = (data: SourceInventoryCleanSubmitReqVO) =>
  requestClient.post<SourceInventoryCleanSubmitRespVO>('/app/source/inventory/clean/submit', data);

/** 获取 AI 清洗 Skill 推荐与安全目录 */
export const recommendCleanSkills = (data: SourceInventoryCleanRecommendReqVO) =>
  requestClient.post<SourceInventoryCleanRecommendRespVO>('/app/source/inventory/clean/recommend', data, { timeout: 0 });

/** 查询 AI 清洗任务状态 */
export const getCleanTaskStatus = (taskId: number) =>
  requestClient.get<SourceInventoryCleanStatusRespVO>('/app/source/inventory/clean/status', {
    params: { taskId },
  });

/** 获取可用清洗 Skill 选项 */
export const getCleanSkillOptions = () =>
  requestClient.get<SourceCleanSkillRespVO[]>('/app/source/inventory/clean/skills');

// ==================== 拉黑卖家 ====================

/** 拉黑卖家分页请求 */
export interface SourceBlockedSellerPageReqVO extends PageParam {
  sellerName?: string;
  /** 状态（0=已解除 1=生效中） */
  status?: number;
  sourceTaskId?: number;
}

/** 拉黑卖家 */
export interface SourceBlockedSellerRespVO {
  id: number;
  sellerId?: string;
  sellerName?: string;
  blockReason?: string;
  /** 来源任务ID（手动添加时为空） */
  sourceTaskId?: number;
  /** 状态（0=已解除 1=生效中） */
  status: number;
  blockTime?: string;
  unblockTime?: string;
  createTime?: string;
}

/** 拉黑卖家保存请求 */
export interface SourceBlockedSellerSaveReqVO {
  /** 记录ID（更新时必填） */
  id?: number;
  sellerId?: string;
  sellerName?: string;
  blockReason: string;
  /** 来源任务ID（从采集结果拉黑时携带） */
  sourceTaskId?: number;
  /** 采集详情ID（从采集结果拉黑时携带，用于补全 sellerId/sellerName） */
  detailId?: number;
}

/** 拉黑卖家 */
export const createSourceBlockedSeller = (data: SourceBlockedSellerSaveReqVO) =>
  requestClient.post<number>('/app/source/blocked-seller/create', data);

/** 编辑拉黑记录（仅拉黑原因） */
export const updateSourceBlockedSeller = (data: SourceBlockedSellerSaveReqVO) =>
  requestClient.put<boolean>('/app/source/blocked-seller/update', data);

/** 解除拉黑 */
export const releaseSourceBlockedSeller = (id: number) =>
  requestClient.put<boolean>('/app/source/blocked-seller/release', null, { params: { id } });

/** 查询拉黑记录详情 */
export const getSourceBlockedSeller = (id: number) =>
  requestClient.get<SourceBlockedSellerRespVO>('/app/source/blocked-seller/get', { params: { id } });

/** 分页查询拉黑卖家 */
export const getSourceBlockedSellerPage = (params: SourceBlockedSellerPageReqVO) =>
  requestClient.get<PageResult<SourceBlockedSellerRespVO>>('/app/source/blocked-seller/page', { params });

// ==================== 图片微调 ====================

/** 图片微调预览请求 */
export interface SourceImageRefinePreviewReqVO {
  inventoryId: number;
  /** 自然语言微调指令 */
  prompt: string;
}

/** 图片微调预览响应 */
export interface SourceImageRefinePreviewRespVO {
  /** 微调后图片地址（已转存 OSS，尚未落库） */
  imageUrl: string;
}

/** 图片微调 - 按指令生成预览图（不落库，耗时较长不设超时） */
export const previewSourceInventoryRefineImage = (data: SourceImageRefinePreviewReqVO) =>
  requestClient.post<SourceImageRefinePreviewRespVO>(
    '/app/source/inventory/refine-image/preview',
    data,
    { timeout: 0 },
  );

/** 图片微调 - 应用预览图为货源主图 */
export const applySourceInventoryRefineImage = (id: number) =>
  requestClient.post<boolean>('/app/source/inventory/refine-image/apply', undefined, {
    params: { id },
  });
