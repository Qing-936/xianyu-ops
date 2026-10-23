package io.github.qing936.xianyuops.module.system.api.logger;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.common.util.object.BeanUtils;
import io.github.qing936.xianyuops.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import io.github.qing936.xianyuops.module.system.api.logger.dto.OperateLogPageReqDTO;
import io.github.qing936.xianyuops.module.system.api.logger.dto.OperateLogRespDTO;
import io.github.qing936.xianyuops.module.system.dal.dataobject.logger.OperateLogDO;
import io.github.qing936.xianyuops.module.system.service.logger.OperateLogService;
import com.fhs.core.trans.anno.TransMethodResult;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 操作日志 API 实现类
 *
 * @author XianyuOps系统
 */
@Service
@Validated
public class OperateLogApiImpl implements OperateLogApi {

    @Resource
    private OperateLogService operateLogService;

    @Override
    public void createOperateLog(OperateLogCreateReqDTO createReqDTO) {
        operateLogService.createOperateLog(createReqDTO);
    }

    @Override
    @TransMethodResult
    public PageResult<OperateLogRespDTO> getOperateLogPage(OperateLogPageReqDTO pageReqDTO) {
        PageResult<OperateLogDO> operateLogPage = operateLogService.getOperateLogPage(pageReqDTO);
        return BeanUtils.toBean(operateLogPage, OperateLogRespDTO.class);
    }

}
