package io.github.qing936.xianyuops.module.infra.api.logger;

import io.github.qing936.xianyuops.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import io.github.qing936.xianyuops.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import io.github.qing936.xianyuops.module.infra.service.logger.ApiErrorLogService;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;

/**
 * API 访问日志的 API 接口
 *
 * @author XianyuOps系统
 */
@Service
@Validated
public class ApiErrorLogApiImpl implements ApiErrorLogCommonApi {

    @Resource
    private ApiErrorLogService apiErrorLogService;

    @Override
    public void createApiErrorLog(ApiErrorLogCreateReqDTO createDTO) {
        apiErrorLogService.createApiErrorLog(createDTO);
    }

}
