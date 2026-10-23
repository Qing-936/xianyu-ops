package io.github.qing936.xianyuops.module.xianyu.controller.admin.source;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerSaveReqVO;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceBlockedSellerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 货源拉黑卖家")
@RestController
@RequestMapping("/app/source/blocked-seller")
@Validated
public class SourceBlockedSellerController {

    @Resource
    private SourceBlockedSellerService sourceBlockedSellerService;

    @PostMapping("/create")
    @Operation(summary = "拉黑卖家")
    @PreAuthorize("@ss.hasPermission('app:source-blocked-seller:create')")
    public CommonResult<Long> createBlockedSeller(@Valid @RequestBody SourceBlockedSellerSaveReqVO reqVO) {
        return success(sourceBlockedSellerService.createBlockedSeller(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "编辑拉黑记录（仅拉黑原因）")
    @PreAuthorize("@ss.hasPermission('app:source-blocked-seller:update')")
    public CommonResult<Boolean> updateBlockedSeller(@Valid @RequestBody SourceBlockedSellerSaveReqVO reqVO) {
        sourceBlockedSellerService.updateBlockedSeller(reqVO);
        return success(true);
    }

    @PutMapping("/release")
    @Operation(summary = "解除拉黑")
    @Parameter(name = "id", description = "记录ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-blocked-seller:update')")
    public CommonResult<Boolean> releaseBlockedSeller(@RequestParam("id") Long id) {
        sourceBlockedSellerService.releaseBlockedSeller(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "查询拉黑记录详情")
    @Parameter(name = "id", description = "记录ID", required = true)
    @PreAuthorize("@ss.hasPermission('app:source-blocked-seller:query')")
    public CommonResult<SourceBlockedSellerRespVO> getBlockedSeller(@RequestParam("id") Long id) {
        return success(sourceBlockedSellerService.getBlockedSeller(id));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询拉黑卖家")
    @PreAuthorize("@ss.hasPermission('app:source-blocked-seller:query')")
    public CommonResult<PageResult<SourceBlockedSellerRespVO>> getBlockedSellerPage(
            @Valid SourceBlockedSellerPageReqVO reqVO) {
        return success(sourceBlockedSellerService.getBlockedSellerPage(reqVO));
    }

}
