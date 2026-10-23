package io.github.qing936.xianyuops.module.system.controller.admin.logger;

import io.github.qing936.xianyuops.framework.apilog.core.annotation.ApiAccessLog;
import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.common.util.object.BeanUtils;
import io.github.qing936.xianyuops.framework.excel.core.util.ExcelUtils;
import io.github.qing936.xianyuops.module.system.controller.admin.logger.vo.loginlog.LoginLogPageReqVO;
import io.github.qing936.xianyuops.module.system.controller.admin.logger.vo.loginlog.LoginLogRespVO;
import io.github.qing936.xianyuops.module.system.dal.dataobject.logger.LoginLogDO;
import io.github.qing936.xianyuops.module.system.service.logger.LoginLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

import static io.github.qing936.xianyuops.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 登录日志")
@RestController
@RequestMapping("/system/login-log")
@Validated
public class LoginLogController {

    @Resource
    private LoginLogService loginLogService;

    @GetMapping("/get")
    @Operation(summary = "获得登录日志")
    @PreAuthorize("@ss.hasPermission('system:login-log:query')")
    public CommonResult<LoginLogRespVO> getLoginLog(Long id) {
        LoginLogDO loginLog = loginLogService.getLoginLog(id);
        return success(BeanUtils.toBean(loginLog, LoginLogRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得登录日志分页列表")
    @PreAuthorize("@ss.hasPermission('system:login-log:query')")
    public CommonResult<PageResult<LoginLogRespVO>> getLoginLogPage(@Valid LoginLogPageReqVO pageReqVO) {
        PageResult<LoginLogDO> pageResult = loginLogService.getLoginLogPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, LoginLogRespVO.class));
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出登录日志 Excel")
    @PreAuthorize("@ss.hasPermission('system:login-log:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportLoginLog(HttpServletResponse response, @Valid LoginLogPageReqVO exportReqVO) throws IOException {
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<LoginLogDO> list = loginLogService.getLoginLogPage(exportReqVO).getList();
        // 输出
        ExcelUtils.write(response, "登录日志.xls", "数据列表", LoginLogRespVO.class,
                BeanUtils.toBean(list, LoginLogRespVO.class));
    }

}
