package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.EventReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentEventDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentEventMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEventLevelEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEventLevelEnum.INFO;

/**
 * 闲鱼 Agent 运行事件记录实现
 * <p>
 * 调用点须在租户上下文内（Agent 侧 Controller 经 TenantUtils.execute 包裹）。
 */
@Service
public class XianyuAgentEventServiceImpl implements XianyuAgentEventService {

    @Resource
    private XianyuAgentEventMapper eventMapper;

    @Resource
    private XianyuAgentAccountService accountService;

    @Override
    public void report(Long agentId, EventReqVO reqVO) {
        XianyuAgentEventDO event = new XianyuAgentEventDO();
        event.setAgentId(agentId);
        // 事件相关账号：有 accountKey 则尝试对齐出 account_id（可空，不影响事件记录）
        UploadReqVO.AccountKeyItem accountKey = reqVO.getAccountKey();
        if (accountKey != null) {
            event.setAccountId(accountService.resolveAccountId(accountKey.getAuthorizeId(), accountKey.getUserNick()));
        }
        event.setEventType(reqVO.getEventType());
        // 级别默认 info
        XianyuAgentEventLevelEnum level = XianyuAgentEventLevelEnum.valueOfLevel(reqVO.getLevel());
        event.setLevel(level != null ? level.getLevel() : INFO.getLevel());
        event.setContent(reqVO.getContent());
        // 明细 JSON 序列化存档（审计回放）
        event.setRawJson(JsonUtils.toJsonString(reqVO.getRawJson()));
        eventMapper.insert(event);
    }

}
