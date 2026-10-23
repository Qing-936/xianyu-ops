package io.github.qing936.xianyuops.module.xianyu.controller.admin.config;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileDetailRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileSaveReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigServiceRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.config.ConfigFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 服务配置")
@RestController
@RequestMapping("/app/config")
@Validated
public class ServiceConfigController {

    @Resource
    private ConfigFileService configFileService;

    @GetMapping("/services")
    @Operation(summary = "获取服务列表")
    @PreAuthorize("@ss.hasPermission('app:config:query')")
    public CommonResult<List<ConfigServiceRespVO>> getServices() {
        return success(configFileService.getServices());
    }

    @GetMapping("/files")
    @Operation(summary = "获取配置文件列表")
    @Parameter(name = "serviceCode", description = "服务编码", required = true)
    @PreAuthorize("@ss.hasPermission('app:config:query')")
    public CommonResult<List<ConfigFileRespVO>> getFiles(@RequestParam("serviceCode") String serviceCode) {
        return success(configFileService.getFiles(serviceCode));
    }

    @GetMapping("/file")
    @Operation(summary = "获取配置文件详情")
    @Parameter(name = "serviceCode", description = "服务编码", required = true)
    @Parameter(name = "fileName", description = "文件名", required = true)
    @PreAuthorize("@ss.hasPermission('app:config:query')")
    public CommonResult<ConfigFileDetailRespVO> getFile(@RequestParam("serviceCode") String serviceCode,
                                                        @RequestParam("fileName") String fileName) {
        return success(configFileService.getFile(serviceCode, fileName));
    }

    @PutMapping("/file")
    @Operation(summary = "保存配置文件")
    @PreAuthorize("@ss.hasPermission('app:config:update')")
    public CommonResult<Boolean> saveFile(@Valid @RequestBody ConfigFileSaveReqVO reqVO) {
        configFileService.saveFile(reqVO);
        return success(true);
    }

    @PostMapping("/file/reset")
    @Operation(summary = "恢复默认配置")
    @Parameter(name = "serviceCode", description = "服务编码", required = true)
    @Parameter(name = "fileName", description = "文件名", required = true)
    @PreAuthorize("@ss.hasPermission('app:config:update')")
    public CommonResult<Boolean> resetFile(@RequestParam("serviceCode") String serviceCode,
                                           @RequestParam("fileName") String fileName) {
        configFileService.resetFile(serviceCode, fileName);
        return success(true);
    }

}
