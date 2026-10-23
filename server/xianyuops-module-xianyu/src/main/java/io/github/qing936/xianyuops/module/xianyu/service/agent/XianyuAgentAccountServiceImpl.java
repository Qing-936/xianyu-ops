package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentAccountDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentAccountMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuLoginStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuLoginStatusEnum.UNKNOWN;

/**
 * 闲鱼 Agent × 闲鱼号账号对齐实现（§6.3）
 * <p>
 * 调用点须在租户上下文内（Agent 侧 Controller 经 TenantUtils.execute 包裹）。
 */
@Service
@Slf4j
public class XianyuAgentAccountServiceImpl implements XianyuAgentAccountService {

    @Resource
    private XianyuAgentAccountMapper agentAccountMapper;

    @Override
    public Long resolveAccountId(String authorizeId, String userNick) {
        // 开源核心版不包含账号主数据，绑定行只保留心跳快照。
        return null;
    }

    @Override
    public void align(Long agentId, HeartbeatReqVO.AccountItem snapshot) {
        // 1. 对齐主数据
        Long accountId = resolveAccountId(snapshot.getAuthorizeId(), snapshot.getUserNick());
        // 2. 更新/建绑定行
        if (accountId != null) {
            alignBound(agentId, accountId, snapshot);
        } else {
            alignUnbound(agentId, snapshot);
        }
    }

    @Override
    public void alignAll(Long agentId, List<HeartbeatReqVO.AccountItem> snapshots) {
        if (snapshots == null) {
            return;
        }
        for (HeartbeatReqVO.AccountItem snapshot : snapshots) {
            align(agentId, snapshot);
        }
        // 3. 历史行收尾：本次心跳未上报的绑定行 login_status 置 unknown
        List<XianyuAgentAccountDO> bindings = agentAccountMapper.selectByAgentId(agentId);
        for (XianyuAgentAccountDO binding : bindings) {
            boolean reported = snapshots.stream().anyMatch(snapshot ->
                    snapshot.getUserNick().equals(binding.getUserNick()));
            if (!reported && !UNKNOWN.getStatus().equals(binding.getLoginStatus())) {
                XianyuAgentAccountDO update = new XianyuAgentAccountDO();
                update.setId(binding.getId());
                update.setLoginStatus(UNKNOWN.getStatus());
                agentAccountMapper.updateById(update);
            }
        }
    }

    /**
     * 已对齐账号：绑定行存在 → update；不存在 → 未对齐行回填 account_id（评审 P2-R2-3）或 insert。
     */
    private void alignBound(Long agentId, Long accountId, HeartbeatReqVO.AccountItem snapshot) {
        XianyuAgentAccountDO binding = agentAccountMapper.selectByAgentIdAndAccountId(agentId, accountId);
        if (binding != null) {
            updateBinding(binding, snapshot, accountId);
            return;
        }
        // 无绑定行：检查未对齐行（agent_id + account_id IS NULL + 双键匹配）→ 回填 account_id
        XianyuAgentAccountDO unbound = agentAccountMapper.selectUnbound(agentId, snapshot.getAuthorizeId(), snapshot.getUserNick());
        if (unbound != null) {
            unbound.setAccountId(accountId); // 回填：未对齐行对齐成功
            updateBinding(unbound, snapshot, accountId);
        } else {
            insertBinding(agentId, accountId, snapshot);
        }
    }

    /**
     * 未对齐账号：按双键（authorize_id + user_nick）查未对齐行 → update；无则 insert（account_id=NULL）。
     * 双键匹配防两个不同昵称的未对齐账号（authorize_id 均为 NULL）互相误配（评审 P2-3）。
     */
    private void alignUnbound(Long agentId, HeartbeatReqVO.AccountItem snapshot) {
        XianyuAgentAccountDO unbound = agentAccountMapper.selectUnbound(agentId, snapshot.getAuthorizeId(), snapshot.getUserNick());
        if (unbound != null) {
            updateBinding(unbound, snapshot, null);
        } else {
            insertBinding(agentId, null, snapshot);
        }
    }

    private void updateBinding(XianyuAgentAccountDO binding, HeartbeatReqVO.AccountItem snapshot, Long accountId) {
        binding.setAccountId(accountId);
        binding.setAuthorizeId(snapshot.getAuthorizeId());
        binding.setUserNick(snapshot.getUserNick());
        binding.setLoginStatus(resolveLoginStatus(snapshot.getLoginStatus()));
        binding.setLastReportAt(LocalDateTime.now());
        agentAccountMapper.updateById(binding);
    }

    private void insertBinding(Long agentId, Long accountId, HeartbeatReqVO.AccountItem snapshot) {
        XianyuAgentAccountDO binding = new XianyuAgentAccountDO();
        binding.setAgentId(agentId);
        binding.setAccountId(accountId);
        binding.setAuthorizeId(snapshot.getAuthorizeId());
        binding.setUserNick(snapshot.getUserNick());
        binding.setLoginStatus(resolveLoginStatus(snapshot.getLoginStatus()));
        binding.setLastReportAt(LocalDateTime.now());
        agentAccountMapper.insert(binding);
    }

    /**
     * 登录状态归一化：非法值兜底为 unknown（心跳上报字段无强校验，不能脏写状态列）。
     */
    private String resolveLoginStatus(String loginStatus) {
        XianyuLoginStatusEnum statusEnum = XianyuLoginStatusEnum.valueOfStatus(loginStatus);
        return statusEnum != null ? statusEnum.getStatus() : UNKNOWN.getStatus();
    }

}
