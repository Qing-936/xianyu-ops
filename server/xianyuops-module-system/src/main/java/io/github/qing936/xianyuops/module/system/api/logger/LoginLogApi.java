package io.github.qing936.xianyuops.module.system.api.logger;

import io.github.qing936.xianyuops.module.system.api.logger.dto.LoginLogCreateReqDTO;

import jakarta.validation.Valid;

/**
 * 登录日志的 API 接口
 *
 * @author XianyuOps系统
 */
public interface LoginLogApi {

    /**
     * 创建登录日志
     *
     * @param reqDTO 日志信息
     */
    void createLoginLog(@Valid LoginLogCreateReqDTO reqDTO);

}
