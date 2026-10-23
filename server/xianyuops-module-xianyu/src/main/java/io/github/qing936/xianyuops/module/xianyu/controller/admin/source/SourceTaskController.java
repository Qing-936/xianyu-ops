package io.github.qing936.xianyuops.module.xianyu.controller.admin.source;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils;
import io.github.qing936.xianyuops.framework.tenant.core.context.TenantContextHolder;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshStatusRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceExecutionRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceItemRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPendingInventoryPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPendingInventoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceInventoryService;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 货源采集任务")
@RestController
@RequestMapping("/app/source/task")
@Validated
public class SourceTaskController {

    @Resource
    private SourceTaskService sourceTaskService;

    @Resource
    private SourceInventoryService sourceInventoryService;

    @PostMapping("/cookie/refresh")
    @Operation(summary = "生成共享 Cookie 刷新二维码")
    @PreAuthorize("@ss.hasPermission('app:source-task:update')")
    public CommonResult<SourceCookieRefreshRespVO> refreshCookieQr() {
        return success(sourceTaskService.refreshCookieQr());
    }

    @GetMapping("/cookie/refresh/status")
    @Operation(summary = "查询共享 Cookie 刷新二维码扫码状态")
    @Parameter(name = "sessionId", description = "扫码会话ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-task:query')")
    public CommonResult<SourceCookieRefreshStatusRespVO> getCookieRefreshStatus(
            @RequestParam("sessionId") String sessionId) {
        return success(sourceTaskService.getCookieRefreshStatus(sessionId));
    }

    @PostMapping("/create")
    @Operation(summary = "创建采集任务")
    @PreAuthorize("@ss.hasPermission('app:source-task:create')")
    public CommonResult<Long> createTask(@Valid @RequestBody SourceTaskCreateReqVO reqVO) {
        return success(sourceTaskService.createTask(reqVO));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询采集任务")
    @PreAuthorize("@ss.hasPermission('app:source-task:query')")
    public CommonResult<PageResult<SourceTaskRespVO>> getTaskPage(@Valid SourceTaskPageReqVO reqVO) {
        return success(sourceTaskService.getTaskPage(reqVO));
    }

    @GetMapping("/pending-inventory-page")
    @Operation(summary = "分页查询尚无任何商品入库的货源任务（只看有数据的最新批次）")
    @PreAuthorize("@ss.hasPermission('app:source-task:query')")
    public CommonResult<PageResult<SourceTaskPendingInventoryRespVO>> getPendingInventoryTaskPage(
            @Valid SourceTaskPendingInventoryPageReqVO reqVO) {
        return success(sourceTaskService.getPendingInventoryTaskPage(reqVO));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除采集任务及采集结果")
    @Parameter(name = "id", description = "任务ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-task:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
        sourceTaskService.deleteTask(id, TenantContextHolder.getRequiredTenantId());
        return success(true);
    }

    @PutMapping("/pause")
    @Operation(summary = "暂停采集任务")
    @Parameter(name = "id", description = "任务ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-task:update')")
    public CommonResult<Boolean> pauseTask(@RequestParam("id") Long id) {
        sourceTaskService.pauseTask(id);
        return success(true);
    }

    @PutMapping("/resume")
    @Operation(summary = "恢复采集任务")
    @Parameter(name = "id", description = "任务ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-task:update')")
    public CommonResult<Boolean> resumeTask(@RequestParam("id") Long id) {
        sourceTaskService.resumeTask(id);
        return success(true);
    }

    @PutMapping("/execute")
    @Operation(summary = "立即执行采集任务")
    @Parameter(name = "id", description = "任务ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-task:update')")
    public CommonResult<Boolean> executeTask(@RequestParam("id") Long id) {
        sourceTaskService.executeTask(id);
        return success(true);
    }

    @PutMapping("/viewed")
    @Operation(summary = "标记任务已查看（记录最后查看批次号）")
    @Parameter(name = "id", description = "任务ID", required = true)
    @Parameter(name = "executionNo", description = "查看的批次号", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-task:query')")
    public CommonResult<Boolean> markViewed(@RequestParam("id") Long id,
                                            @RequestParam("executionNo") Integer executionNo) {
        sourceTaskService.markViewed(id, executionNo);
        return success(true);
    }

    @GetMapping("/execution/list")
    @Operation(summary = "查询任务执行批次列表")
    @Parameter(name = "taskId", description = "任务ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-task:query')")
    public CommonResult<List<SourceExecutionRespVO>> getExecutionList(@RequestParam("taskId") Long taskId) {
        return success(sourceTaskService.getExecutionList(taskId));
    }

    @GetMapping("/item/list")
    @Operation(summary = "查询批次搜索结果")
    @PreAuthorize("@ss.hasPermission('app:source-task:query')")
    public CommonResult<List<SourceItemRespVO>> getItemList(
            @RequestParam("taskId") Long taskId,
            @RequestParam("executionNo") Integer executionNo) {
        return success(sourceTaskService.getItemList(taskId, executionNo));
    }

    @PostMapping("/item/inventory")
    @Operation(summary = "将采集详情加入货源库")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:create')")
    public CommonResult<Boolean> addInventory(@RequestParam("detailId") Long detailId) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        sourceInventoryService.addFromSourceDetail(detailId, String.valueOf(userId), tenantId);
        return success(true);
    }

    @GetMapping("/export-csv")
    @Operation(summary = "导出任务数据CSV")
    @PreAuthorize("@ss.hasPermission('app:source-task:export')")
    public void exportCsv(@RequestParam("taskId") Long taskId, HttpServletResponse response) {
        sourceTaskService.exportCsv(taskId, response);
    }

}
