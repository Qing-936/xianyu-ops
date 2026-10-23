package io.github.qing936.xianyuops.module.xianyu.service.source;

import cn.hutool.core.util.StrUtil;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.common.util.object.BeanUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshStatusRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceExecutionRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceItemRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPendingInventoryPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPendingInventoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceExecutionDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceInventoryDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceItemDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceTaskDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceExecutionMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceInventoryMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceItemDetailDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceItemDetailMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceItemMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceTaskMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskErrorCodeConstants.*;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskStatusEnum.*;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskTriggerModeEnum.*;

/**
 * 货源采集任务 Service 实现
 */
@Service
@Slf4j
@Validated
public class SourceTaskServiceImpl implements SourceTaskService {

    private static final DateTimeFormatter CSV_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private SourceTaskMapper sourceTaskMapper;

    @Resource
    private SourceExecutionMapper sourceExecutionMapper;

    @Resource
    private SourceItemMapper sourceItemMapper;

    @Resource
    private SourceItemDetailMapper sourceItemDetailMapper;

    @Resource
    private SourceInventoryMapper sourceInventoryMapper;

    @Resource
    private PythonSourceNotifier pythonSourceNotifier;

    @Resource
    private SourceBlockedSellerService sourceBlockedSellerService;

    @Override
    public SourceCookieRefreshRespVO refreshCookieQr() {
        return pythonSourceNotifier.refreshCookieQr();
    }

    @Override
    public SourceCookieRefreshStatusRespVO getCookieRefreshStatus(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return pythonSourceNotifier.getCookieRefreshStatus(sessionId);
    }

    // ==================== Task 3.2 : createTask ====================

    @Override
    public Long createTask(SourceTaskCreateReqVO reqVO) {
        // 1. 自定义业务校验
        validateCreateReq(reqVO);

        // 2. 构建 DO（status=0 运行中，executedCount=0）
        SourceTaskDO task = BeanUtils.toBean(reqVO, SourceTaskDO.class);
        task.setStatus(RUNNING.getStatus());
        task.setExecutedCount(0);
        task.setErrorMessage("");

        // 3. 入库
        sourceTaskMapper.insert(task);

        // 4. 仅一次性任务（triggerMode=0）立即提交给 Python；定时任务等待扫描器触发
        if (ONCE.getMode().equals(reqVO.getTriggerMode())) {
            submitToPython(task.getId(), false, false);
        }

        return task.getId();
    }

    /**
     * 提交任务给 Python 服务，失败则更新状态为提交失败
     */
    private boolean submitToPython(Long taskId, boolean switchCookie, boolean propagateFailure) {
        try {
            return pythonSourceNotifier.notifyTaskCreated(taskId, switchCookie);
        } catch (Exception e) {
            log.error("[submitToPython][taskId={} 提交Python失败]", taskId, e);
            SourceTaskDO update = new SourceTaskDO();
            update.setId(taskId);
            update.setStatus(SUBMIT_FAILED.getStatus()); // 提交失败
            update.setErrorMessage(e.getMessage() != null ? e.getMessage() : "未知错误");
            sourceTaskMapper.updateById(update);
            if (propagateFailure) {
                throw new RuntimeException("提交 Python 采集任务失败: " + update.getErrorMessage(), e);
            }
            return false;
        }
    }

    /**
     * 创建任务参数校验
     * - 价格范围：filterMinPrice > filterMaxPrice → 抛 SOURCE_TASK_PRICE_RANGE_INVALID
     * - 浏览量范围：filterViewCountMin > filterViewCountMax → 抛 SOURCE_TASK_VIEW_COUNT_RANGE_INVALID
     * - 想要量范围：filterWantCountMin > filterWantCountMax → 抛 SOURCE_TASK_WANT_COUNT_RANGE_INVALID
     * - 定时任务缺执行时间 → 抛 SOURCE_TASK_SCHEDULE_TIME_REQUIRED
     */
    private void validateCreateReq(SourceTaskCreateReqVO reqVO) {
        if (reqVO.getFilterMinPrice() != null && reqVO.getFilterMaxPrice() != null
                && reqVO.getFilterMinPrice() > reqVO.getFilterMaxPrice()) {
            throw exception(SOURCE_TASK_PRICE_RANGE_INVALID);
        }
        if (reqVO.getFilterViewCountMin() != null && reqVO.getFilterViewCountMax() != null
                && reqVO.getFilterViewCountMin() > reqVO.getFilterViewCountMax()) {
            throw exception(SOURCE_TASK_VIEW_COUNT_RANGE_INVALID);
        }
        if (reqVO.getFilterWantCountMin() != null && reqVO.getFilterWantCountMax() != null
                && reqVO.getFilterWantCountMin() > reqVO.getFilterWantCountMax()) {
            throw exception(SOURCE_TASK_WANT_COUNT_RANGE_INVALID);
        }
        if (DAILY.getMode().equals(reqVO.getTriggerMode())
                && StrUtil.isBlank(reqVO.getScheduleTime())) {
            throw exception(SOURCE_TASK_SCHEDULE_TIME_REQUIRED);
        }
    }

    // ==================== Task 3.3 : getTaskPage ====================

    @Override
    public PageResult<SourceTaskRespVO> getTaskPage(SourceTaskPageReqVO reqVO) {
        PageResult<SourceTaskDO> page = sourceTaskMapper.selectPage(reqVO);
        PageResult<SourceTaskRespVO> result = BeanUtils.toBean(page, SourceTaskRespVO.class);
        if (result.getList().isEmpty()) {
            return result;
        }
        Map<Long, SourceExecutionDO> latestExecutions = buildLatestExecutionMap(
                sourceExecutionMapper.selectByTaskIds(result.getList().stream()
                        .map(SourceTaskRespVO::getId)
                        .toList()));
        result.getList().forEach(task -> {
            SourceExecutionDO latestExecution = latestExecutions.get(task.getId());
            task.setRetryable(isRetryable(task, latestExecution));
            task.setLatestExecutionEmpty(isLatestExecutionEmpty(latestExecution));
        });
        return result;
    }

    @Override
    public PageResult<SourceTaskPendingInventoryRespVO> getPendingInventoryTaskPage(
            SourceTaskPendingInventoryPageReqVO reqVO) {
        // shortcut: Loads the task summary set before paging; move paging into SQL if task counts grow large.
        List<SourceTaskPendingInventoryRespVO> rows = sourceTaskMapper.selectPendingInventoryTaskSummaryList();
        long totalPendingResultCount = rows.stream()
                .mapToLong(row -> row.getPendingResultCount() == null ? 0 : row.getPendingResultCount())
                .sum();
        rows.forEach(row -> row.setTotalPendingResultCount(totalPendingResultCount));
        long offset = (long) (reqVO.getPageNo() - 1) * reqVO.getPageSize();
        if (offset >= rows.size()) {
            return PageResult.empty((long) rows.size());
        }
        int fromIndex = (int) offset;
        int toIndex = Math.min(fromIndex + reqVO.getPageSize(), rows.size());
        return new PageResult<>(rows.subList(fromIndex, toIndex), (long) rows.size());
    }

    @Override
    public void deleteTask(Long id, Long tenantId) {
        SourceTaskDO task = validateTaskExists(id);
        if (RUNNING.getStatus().equals(task.getStatus())) {
            SourceTaskDO pausedTask = new SourceTaskDO();
            pausedTask.setId(id);
            pausedTask.setStatus(PAUSED.getStatus());
            sourceTaskMapper.updateById(pausedTask);
        }
        pythonSourceNotifier.deleteTask(id, tenantId);
    }

    private Map<Long, SourceExecutionDO> buildLatestExecutionMap(List<SourceExecutionDO> executions) {
        Map<Long, SourceExecutionDO> latestExecutions = new HashMap<>();
        for (SourceExecutionDO execution : executions) {
            SourceExecutionDO existing = latestExecutions.get(execution.getTaskId());
            if (existing == null || execution.getExecutionNo() > existing.getExecutionNo()) {
                latestExecutions.put(execution.getTaskId(), execution);
            }
        }
        return latestExecutions;
    }

    private boolean isRetryable(SourceTaskRespVO task, SourceExecutionDO latestExecution) {
        if (SUBMIT_FAILED.getStatus().equals(task.getStatus()) || FAILED.getStatus().equals(task.getStatus())) {
            return true;
        }
        return DAILY.getMode().equals(task.getTriggerMode())
                && RUNNING.getStatus().equals(task.getStatus())
                && latestExecution != null
                && Integer.valueOf(2).equals(latestExecution.getStatus());
    }

    private boolean isLatestExecutionEmpty(SourceExecutionDO latestExecution) {
        return latestExecution != null
                && (latestExecution.getValidCount() == null || latestExecution.getValidCount() <= 0);
    }

    // ==================== Task 3.4 : pauseTask & resumeTask ====================

    @Override
    public void pauseTask(Long id) {
        SourceTaskDO task = validateTaskExists(id);
        // 仅运行中（status=0）的任务可以暂停
        if (!RUNNING.getStatus().equals(task.getStatus())) {
            throw exception(SOURCE_TASK_CANNOT_PAUSE);
        }
        SourceTaskDO update = new SourceTaskDO();
        update.setId(id);
        update.setStatus(PAUSED.getStatus());
        sourceTaskMapper.updateById(update);
    }

    @Override
    public void resumeTask(Long id) {
        SourceTaskDO task = validateTaskExists(id);
        // 已暂停（status=1）或已完成（status=2，历史每日任务被旧版 Python 逻辑误置为完成）的任务可以恢复
        if (!PAUSED.getStatus().equals(task.getStatus())
                && !COMPLETED.getStatus().equals(task.getStatus())) {
            throw exception(SOURCE_TASK_CANNOT_RESUME);
        }
        SourceTaskDO update = new SourceTaskDO();
        update.setId(id);
        update.setStatus(RUNNING.getStatus());
        sourceTaskMapper.updateById(update);
    }

    /**
     * 校验任务存在，不存在则抛出 SOURCE_TASK_NOT_EXISTS
     */
    private SourceTaskDO validateTaskExists(Long id) {
        SourceTaskDO task = sourceTaskMapper.selectById(id);
        if (task == null) {
            throw exception(SOURCE_TASK_NOT_EXISTS);
        }
        return task;
    }

    // ==================== markViewed ====================

    @Override
    public void markViewed(Long id, Integer executionNo) {
        SourceTaskDO task = validateTaskExists(id);
        if (executionNo == null) {
            return;
        }
        Integer lastViewed = task.getLastViewedExecutionNo();
        // 仅当批次号更大时更新，避免查看旧批次时回退标记
        if (lastViewed != null && lastViewed >= executionNo) {
            return;
        }
        SourceTaskDO update = new SourceTaskDO();
        update.setId(id);
        update.setLastViewedExecutionNo(executionNo);
        sourceTaskMapper.updateById(update);
    }

    // ==================== executeTask ====================

    @Override
    public void executeTask(Long id) {
        SourceTaskDO task = validateTaskExists(id);
        // 仅运行中(0)、提交失败(3)或失败(4)的任务可立即执行
        if (!RUNNING.getStatus().equals(task.getStatus())
                && !SUBMIT_FAILED.getStatus().equals(task.getStatus())
                && !FAILED.getStatus().equals(task.getStatus())) {
            throw exception(SOURCE_TASK_CANNOT_EXECUTE);
        }
        // 重置状态为运行中，清空错误信息
        SourceTaskDO update = new SourceTaskDO();
        update.setId(id);
        update.setStatus(RUNNING.getStatus());
        update.setErrorMessage("");
        sourceTaskMapper.updateById(update);

        // 提交给 Python
        boolean accepted = submitToPython(id, true, true);
        if (!accepted) {
            SourceTaskDO restore = new SourceTaskDO();
            restore.setId(id);
            restore.setStatus(task.getStatus());
            restore.setErrorMessage(task.getErrorMessage());
            sourceTaskMapper.updateById(restore);
            throw exception(SOURCE_TASK_RETRY_BUSY);
        }
    }

    @Override
    public void executeScheduledTask(Long id) {
        validateTaskExists(id);
        submitToPython(id, false, true);
    }

    // ==================== Task 3.5 : getExecutionList & getItemList ====================

    @Override
    public List<SourceExecutionRespVO> getExecutionList(Long taskId) {
        validateTaskExists(taskId);
        List<SourceExecutionDO> list = sourceExecutionMapper.selectByTaskId(taskId);
        return BeanUtils.toBean(list, SourceExecutionRespVO.class);
    }

    @Override
    public List<SourceItemRespVO> getItemList(Long taskId, Integer executionNo) {
        validateTaskExists(taskId);
        List<SourceItemDO> list = sourceItemMapper.selectValidItems(taskId, executionNo);
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        // 1. 详情映射（含 sellerId，用于黑名单匹配）
        Map<Long, SourceItemDetailDO> detailMap = sourceItemDetailMapper.selectListByItemIds(
                        list.stream().map(SourceItemDO::getId).toList())
                .stream().collect(Collectors.toMap(SourceItemDetailDO::getItemId, detail -> detail,
                        SourceTaskServiceImpl::latestDetail));
        // 2. 排除黑名单卖家（sellerId 优先、sellerName 兜底）
        Set<String> blockedSellerIds = sourceBlockedSellerService.getEffectiveSellerIds();
        Set<String> blockedSellerNames = sourceBlockedSellerService.getEffectiveSellerNames();
        List<SourceItemDO> candidates = list.stream()
                .filter(item -> !isBlockedSeller(item, detailMap.get(item.getId()),
                        blockedSellerIds, blockedSellerNames))
                .toList();
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> externalItemIds = candidates.stream()
                .map(SourceItemDO::getExternalItemId)
                .filter(itemId -> itemId != null && !itemId.isBlank())
                .collect(Collectors.toSet());
        Map<String, SourceInventoryDO> inventoriesByExternalItemId = sourceInventoryMapper
                .selectListByExternalItemIds(externalItemIds).stream()
                .collect(Collectors.toMap(SourceInventoryDO::getExternalItemId, inventory -> inventory,
                        SourceTaskServiceImpl::latestInventory));

        Map<String, SourceItemDO> previousItemsByExternalItemId = sourceItemMapper
                .selectPreviousItems(taskId, executionNo, externalItemIds).stream()
                .filter(item -> item.getExternalItemId() != null && !item.getExternalItemId().isBlank())
                .collect(Collectors.toMap(SourceItemDO::getExternalItemId, item -> item,
                        SourceTaskServiceImpl::latestItem));
        List<Long> previousItemIds = previousItemsByExternalItemId.values().stream()
                .map(SourceItemDO::getId).toList();
        Map<Long, SourceItemDetailDO> previousDetailsByItemId = previousItemIds.isEmpty() ? Map.of()
                : sourceItemDetailMapper.selectListByItemIds(previousItemIds).stream()
                .collect(Collectors.toMap(SourceItemDetailDO::getItemId, detail -> detail,
                        SourceTaskServiceImpl::latestDetail));

        // 3. 隐藏同任务较早批次中内容未变化且尚未入库的重复商品
        List<SourceItemDO> visibleItems = candidates.stream()
                .filter(item -> {
                    String externalItemId = item.getExternalItemId();
                    if (externalItemId == null || externalItemId.isBlank()
                            || inventoriesByExternalItemId.containsKey(externalItemId)) {
                        return true;
                    }
                    SourceItemDO previousItem = previousItemsByExternalItemId.get(externalItemId);
                    if (previousItem == null) {
                        return true;
                    }
                    SourceItemDetailDO currentDetail = detailMap.get(item.getId());
                    SourceItemDetailDO previousDetail = previousDetailsByItemId.get(previousItem.getId());
                    return currentDetail == null || previousDetail == null
                            || !hasSameProductContent(currentDetail, previousDetail);
                })
                .toList();
        if (visibleItems.isEmpty()) {
            return new ArrayList<>();
        }
        List<SourceItemRespVO> result = BeanUtils.toBean(visibleItems, SourceItemRespVO.class);

        // 4. 补齐详情与货源库关联；老记录按 detailId 兜底
        Map<Long, SourceItemDO> visibleItemsById = visibleItems.stream()
                .collect(Collectors.toMap(SourceItemDO::getId, item -> item));
        Map<Long, Long> detailIds = visibleItems.stream()
                .map(item -> detailMap.get(item.getId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SourceItemDetailDO::getItemId, SourceItemDetailDO::getId,
                        SourceTaskServiceImpl::latestDetailId));
        Map<Long, SourceInventoryDO> inventoriesByDetailId = detailIds.isEmpty()
                ? Map.of()
                : sourceInventoryMapper.selectListBySourceDetailIds(detailIds.values().stream().toList())
                .stream().collect(Collectors.toMap(SourceInventoryDO::getSourceDetailId, inventory -> inventory,
                        SourceTaskServiceImpl::latestInventory));
        result.forEach(item -> {
            Long detailId = detailIds.get(item.getId());
            item.setDetailId(detailId);
            String externalItemId = visibleItemsById.get(item.getId()).getExternalItemId();
            SourceInventoryDO inventory = externalItemId == null ? null
                    : inventoriesByExternalItemId.get(externalItemId);
            if (inventory == null && detailId != null) {
                inventory = inventoriesByDetailId.get(detailId);
            }
            if (inventory != null) {
                item.setSourceInventoryId(inventory.getId());
            }
        });
        return result;
    }

    private static boolean hasSameProductContent(SourceItemDetailDO first, SourceItemDetailDO second) {
        if (first.getTitle() == null || second.getTitle() == null
                || first.getDescription() == null || second.getDescription() == null
                || first.getPrice() == null || second.getPrice() == null
                || first.getPicUrl() == null || first.getPicUrl().isBlank()
                || second.getPicUrl() == null || second.getPicUrl().isBlank()
                || first.getImages() == null || second.getImages() == null) {
            return false;
        }
        return Objects.equals(first.getTitle(), second.getTitle())
                && Objects.equals(first.getDescription(), second.getDescription())
                && Objects.equals(first.getPrice(), second.getPrice())
                && Objects.equals(first.getOriginalPrice(), second.getOriginalPrice())
                && Objects.equals(first.getPicUrl(), second.getPicUrl())
                && Objects.equals(first.getImages(), second.getImages());
    }

    private static SourceItemDO latestItem(SourceItemDO first, SourceItemDO second) {
        int executionOrder = Integer.compare(first.getExecutionNo(), second.getExecutionNo());
        if (executionOrder != 0) {
            return executionOrder > 0 ? first : second;
        }
        return first.getId() >= second.getId() ? first : second;
    }

    private static SourceItemDetailDO latestDetail(SourceItemDetailDO first, SourceItemDetailDO second) {
        return first.getId() >= second.getId() ? first : second;
    }

    private static Long latestDetailId(Long first, Long second) {
        return Math.max(first, second);
    }

    private static SourceInventoryDO latestInventory(SourceInventoryDO first, SourceInventoryDO second) {
        return first.getId() >= second.getId() ? first : second;
    }

    /**
     * 判断采集结果是否属于黑名单卖家：详情 sellerId 命中，或 item sellerName 命中
     */
    private boolean isBlockedSeller(SourceItemDO item, SourceItemDetailDO detail,
                                    Set<String> blockedSellerIds, Set<String> blockedSellerNames) {
        if (detail != null && detail.getSellerId() != null && blockedSellerIds.contains(detail.getSellerId())) {
            return true;
        }
        return item.getSellerName() != null && blockedSellerNames.contains(item.getSellerName());
    }

    // ==================== Task 3.6 : exportCsv ====================

    @Override
    public void exportCsv(Long taskId, HttpServletResponse response) {
        SourceTaskDO task = validateTaskExists(taskId);
        List<SourceItemDO> items = sourceItemMapper.selectAllByTaskId(taskId);

        // 编码文件名，防止中文乱码
        String encodedFileName = URLEncoder.encode(
                "货源数据_" + task.getProductName() + ".csv", StandardCharsets.UTF_8)
                .replace("+", "%20");

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + encodedFileName);

        try {
            // 写入 UTF-8 BOM（0xEF 0xBB 0xBF），确保 Excel 正确识别中文
            response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

            PrintWriter writer = new PrintWriter(
                    new java.io.OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));

            // 写入 CSV 表头（与"查看结果"弹窗表头对齐）
            writer.println("关键词,卖家,价格,获取日期,商品链接,浏览量,想要量,热度,高价值,竞争力,卖家级别");

            // 写入数据行
            for (SourceItemDO item : items) {
                String keyword = escapeCsvField(item.getSearchKeyword());
                String sellerName = escapeCsvField(item.getSellerName());
                String price = item.getPrice() != null
                        ? String.format("%.2f", item.getPrice() / 100.0) : "";
                String createTime = item.getCreateTime() != null
                        ? item.getCreateTime().format(CSV_DATE_FMT) : "";
                String detailUrl = escapeCsvField(item.getDetailUrl());
                String viewCount = item.getViewCount() != null ? String.valueOf(item.getViewCount()) : "";
                String wantCount = item.getWantCount() != null ? String.valueOf(item.getWantCount()) : "";
                String heatLevel = escapeCsvField(item.getHeatLevel());
                String highValue = escapeCsvField(item.getHighValue());
                String competitiveness = escapeCsvField(item.getCompetitiveness());
                String sellerLevel = escapeCsvField(item.getSellerLevel());

                writer.println(keyword + "," + sellerName + "," + price + "," + createTime + ","
                        + detailUrl + "," + viewCount + "," + wantCount + ","
                        + heatLevel + "," + highValue + "," + competitiveness + ","
                        + sellerLevel);
            }

            writer.flush();
        } catch (IOException e) {
            log.error("[exportCsv][taskId={} 导出CSV异常]", taskId, e);
            throw new RuntimeException("CSV 导出失败", e);
        }
    }

    /**
     * 转义 CSV 字段：若字段含逗号、双引号或换行，则用双引号包裹，内部双引号转义为两个双引号
     */
    private String escapeCsvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

}
