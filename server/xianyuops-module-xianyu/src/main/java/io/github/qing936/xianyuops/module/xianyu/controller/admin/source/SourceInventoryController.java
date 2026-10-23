package io.github.qing936.xianyuops.module.xianyu.controller.admin.source;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils;
import io.github.qing936.xianyuops.framework.tenant.core.context.TenantContextHolder;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryManualImportReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryManualImportRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportParseRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportResultRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCleanSkillRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanStatusRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanRecommendReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanRecommendRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceImageRefinePreviewReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceImageRefinePreviewRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceInventoryService;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceInventoryImportService;
import io.github.qing936.xianyuops.module.xianyu.service.source.refine.SourceImageRefineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 货源库")
@RestController
@RequestMapping("/app/source/inventory")
@Validated
public class SourceInventoryController {

    @Resource
    private SourceInventoryService sourceInventoryService;

    @Resource
    private SourceInventoryImportService sourceInventoryImportService;

    @Resource
    private SourceImageRefineService sourceImageRefineService;

    @PostMapping("/manual-import")
    @Operation(summary = "人工导入闲鱼商品到货源库")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:create')")
    public CommonResult<SourceInventoryManualImportRespVO> manualImport(@Valid @RequestBody SourceInventoryManualImportReqVO reqVO) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return success(sourceInventoryService.manualImportBatch(reqVO.getDetailUrls(), String.valueOf(userId), tenantId));
    }

    @PostMapping("/refresh")
    @Operation(summary = "重新采集并刷新货源")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:update')")
    public CommonResult<Boolean> refresh(@RequestParam("id") Long id) {
        sourceInventoryService.refreshInventory(id, String.valueOf(SecurityFrameworkUtils.getLoginUserId()),
                TenantContextHolder.getRequiredTenantId());
        return success(true);
    }

    @PostMapping("/import/parse")
    @Operation(summary = "批量导入-解析 Excel 生成预览（不落库）")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:create')")
    public CommonResult<List<SourceInventoryImportParseRespVO>> importParse(@RequestParam("file") MultipartFile file) {
        return success(sourceInventoryImportService.parse(file));
    }

    @PostMapping("/import/create")
    @Operation(summary = "批量导入-确认导入到货源库（逐条写入，单条失败不影响其他）")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:create')")
    public CommonResult<List<SourceInventoryImportResultRespVO>> importCreate(@Valid @RequestBody SourceInventoryImportCreateReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return success(sourceInventoryImportService.create(reqVO, String.valueOf(userId)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询货源库")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:query')")
    public CommonResult<PageResult<SourceInventoryRespVO>> getPage(@Valid SourceInventoryPageReqVO reqVO) {
        return success(sourceInventoryService.getInventoryPage(reqVO));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除货源")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        sourceInventoryService.deleteInventory(id);
        return success(true);
    }

    @PostMapping("/batch-delete")
    @Operation(summary = "批量删除货源")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:delete')")
    public CommonResult<Boolean> batchDelete(@RequestBody List<Long> ids) {
        sourceInventoryService.deleteBatch(ids);
        return success(true);
    }

    @GetMapping("/export-csv")
    @Operation(summary = "导出货源库 CSV")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:export')")
    public void exportCsv(@Valid SourceInventoryPageReqVO reqVO, HttpServletResponse response) {
        sourceInventoryService.exportCsv(reqVO, response);
    }

    @PostMapping("/clean/submit")
    @Operation(summary = "提交 AI 清洗任务")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:update')")
    public CommonResult<SourceInventoryCleanSubmitRespVO> submitCleanTask(@Valid @RequestBody SourceInventoryCleanSubmitReqVO reqVO) {
        return success(sourceInventoryService.submitCleanTask(reqVO));
    }

    @PostMapping("/clean/recommend")
    @Operation(summary = "推荐货源分类与清洗 Skill")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:update')")
    public CommonResult<SourceInventoryCleanRecommendRespVO> recommendCleanSkills(
            @Valid @RequestBody SourceInventoryCleanRecommendReqVO reqVO) {
        return success(sourceInventoryService.recommendCleanSkills(reqVO.getInventoryIds()));
    }

    @GetMapping("/clean/status")
    @Operation(summary = "查询 AI 清洗任务状态")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:query')")
    public CommonResult<SourceInventoryCleanStatusRespVO> getCleanTaskStatus(@RequestParam("taskId") Long taskId) {
        return success(sourceInventoryService.getCleanTaskStatus(taskId));
    }

    @GetMapping("/clean/skills")
    @Operation(summary = "获取可用清洗 Skill 选项")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:query')")
    public CommonResult<List<SourceCleanSkillRespVO>> getCleanSkillOptions() {
        return success(sourceInventoryService.getCleanSkillOptions());
    }

    @PostMapping("/refine-image/preview")
    @Operation(summary = "图片微调-按指令生成预览图（不落库）")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:update')")
    public CommonResult<SourceImageRefinePreviewRespVO> refineImagePreview(
            @Valid @RequestBody SourceImageRefinePreviewReqVO reqVO) {
        String imageUrl = sourceImageRefineService.preview(reqVO.getInventoryId(), reqVO.getPrompt(),
                SecurityFrameworkUtils.getLoginUserId());
        return success(new SourceImageRefinePreviewRespVO(imageUrl));
    }

    @PostMapping("/refine-image/apply")
    @Operation(summary = "图片微调-应用预览图为货源主图")
    @PreAuthorize("@ss.hasPermission('app:source-inventory:update')")
    public CommonResult<Boolean> refineImageApply(@RequestParam("id") Long id) {
        sourceImageRefineService.apply(id);
        return success(true);
    }
}
