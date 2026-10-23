package io.github.qing936.xianyuops.module.infra.api.logger;

import io.github.qing936.xianyuops.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import io.github.qing936.xianyuops.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import io.github.qing936.xianyuops.module.infra.service.logger.ApiAccessLogService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * API 访问日志的 API 实现类
 *
 * @author XianyuOps系统
 */
@Service
@Validated
public class ApiAccessLogApiImpl implements ApiAccessLogCommonApi {

    @Resource
    private ApiAccessLogService apiAccessLogService;

    @Override
    public void createApiAccessLog(ApiAccessLogCreateReqDTO createDTO) {
        apiAccessLogService.createApiAccessLog(createDTO);
    }

}
