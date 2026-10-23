package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentAccountRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEventPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEventRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuMachineAgentPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuMachineAgentRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.EventReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentAccountDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentEventDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuReplyTaskDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentAccountMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentEventMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuReplyTaskMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.framework.common.util.collection.CollectionUtils.convertList;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_AGENT_NOT_EXISTS;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEventTypeEnum.AGENT_START;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentStatusEnum.ONLINE;

/**
 * 闲鱼机器 Agent 服务实现
 * <p>
 * 心跳路径调用点须在租户上下文内（Agent 侧 Controller 经 TenantUtils.execute 包裹）。
 */
@Service
@Slf4j
public class XianyuAgentServiceImpl implements XianyuAgentService {

    @Resource
    private XianyuAgentMapper agentMapper;

    @Resource
    private XianyuAgentAccountMapper agentAccountMapper;

    @Resource
    private XianyuAgentEventMapper eventMapper;

    @Resource
    private XianyuReplyTaskMapper replyTaskMapper;

    @Resource
    private XianyuAgentAccountService accountService;

    @Resource
    private XianyuAgentEventService eventService;

    @Resource
    private XianyuAgentProperties properties;

    @Resource
    private XianyuAgentTokenService tokenService;

    @Resource
    private XianyuAgentEnrollService enrollService;

    @Override
    public HeartbeatRespVO heartbeat(HeartbeatReqVO reqVO) {
        // 身份判定：Filter 已校验凭据并写入上下文，两类互斥——
        //   注册码（enrollId 非空）→ 首次注册 / 未激活补发；专属 Token（agentId 非空）→ 须与 machineCode 一致
        Long enrollId = XianyuAgentContext.getEnrollId();
        Long ctxAgentId = XianyuAgentContext.getAgentId();
        XianyuAgentDO agent = agentMapper.selectByMachineCode(reqVO.getMachineCode());
        String issuedToken = null;
        if (enrollId != null) {
            if (agent == null) {
                agent = register(reqVO);
            } else if (agent.getTokenHash() != null) {
                // 机器已激活：注册码不受理重发（否则克隆机可用一张新码顶掉在跑的机器），重发走管理端「重置 Token」
                log.warn("[xianyu-agent] Agent(id={}) 已激活专属 Token，注册码(id={}) 不再受理", agent.getId(), enrollId);
                throw new AgentTokenMismatchException("该机器已激活专属 Token，注册码不再受理；如需重发请先在管理端重置 Token");
            } else {
                // 已注册但从未领到 Token（上次响应丢失等）→ 本次心跳补发
                updateHeartbeat(agent, reqVO);
                log.info("[xianyu-agent] Agent(id={}) 此前未激活，本次心跳用注册码(id={}) 补发专属 Token",
                        agent.getId(), enrollId);
            }
            // 先消费注册码再签发：签发异常不会留下「Token 已下发但注册码仍可用」的窗口
            enrollService.consume(enrollId, agent.getId());
            if (agent.getTokenHash() == null) { // 并发注册重查分支可能已被另一进程激活
                issuedToken = tokenService.issueToken(agent.getId());
            }
        } else if (agent == null) {
            // 专属 Token 携带未知机器指纹 = 克隆机未重置（05 脚本漏清 .agent_token）
            log.warn("[xianyu-agent] 专属 Token(agentId={}) 携带未知机器指纹，疑似克隆机未重置专属 Token，拒绝注册: machineCode={}",
                    ctxAgentId, reqVO.getMachineCode());
            throw new AgentTokenMismatchException("Token 与机器指纹不匹配（疑似克隆机未重置专属 Token），拒绝注册");
        } else if (!Objects.equals(ctxAgentId, agent.getId())) {
            log.warn("[xianyu-agent] Token 身份(agentId={}) 与机器指纹对应 Agent(id={}) 不一致，疑似克隆机未重置专属 Token，拒绝",
                    ctxAgentId, agent.getId());
            throw new AgentTokenMismatchException("Token 与机器指纹不匹配（疑似克隆机未重置专属 Token）");
        } else {
            updateHeartbeat(agent, reqVO);
        }
        // 账号对齐（本次心跳未出现的绑定行 login_status 置 unknown）
        accountService.alignAll(agent.getId(), reqVO.getAccounts());
        // 组装响应
        HeartbeatRespVO respVO = new HeartbeatRespVO();
        respVO.setAgentId(agent.getId());
        // 服务端时间 ISO-8601（框架 Jackson 将 LocalDateTime 序列化为 EpochMillis，契约要求 String，此处手写格式化）
        respVO.setServerTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        respVO.setPollIntervalSec(properties.getPollIntervalSec());
        respVO.setPendingTaskCount(countPendingTasks(agent.getId()));
        // 专属 Token 明文仅注册/补发时返回一次（Agent 端持久化到 .agent_token）
        respVO.setAgentToken(issuedToken);
        return respVO;
    }

    /**
     * 首次注册：insert + agent_start 事件。
     * uk_machine_code 并发防御：DuplicateKeyException 时重查（两个 Agent 同时首跳同一机器）。
     */
    private XianyuAgentDO register(HeartbeatReqVO reqVO) {
        XianyuAgentDO agent = new XianyuAgentDO();
        agent.setName(reqVO.getHostName());
        agent.setMachineCode(reqVO.getMachineCode());
        agent.setHostName(reqVO.getHostName());
        agent.setOsVersion(reqVO.getOsVersion());
        agent.setAgentVersion(reqVO.getAgentVersion());
        agent.setCapabilities(normalizeCapabilities(reqVO.getCapabilities()));
        agent.setScreenLocked(Boolean.TRUE.equals(reqVO.getScreenLocked()));
        agent.setLastHeartbeatAt(LocalDateTime.now());
        agent.setStatus(ONLINE.getStatus());
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException e) {
            // 并发注册：重查已存在行并走更新分支
            log.info("[xianyu-agent] 注册撞唯一键，重查 machineCode={}", reqVO.getMachineCode());
            XianyuAgentDO existing = agentMapper.selectByMachineCode(reqVO.getMachineCode());
            if (existing == null) {
                throw e;
            }
            updateHeartbeat(existing, reqVO);
            return existing;
        }
        // 首次注册事件
        EventReqVO startEvent = new EventReqVO();
        startEvent.setEventType(AGENT_START.getType());
        startEvent.setLevel("info");
        startEvent.setContent("Agent 首次注册上线");
        eventService.report(agent.getId(), startEvent);
        return agent;
    }

    private void updateHeartbeat(XianyuAgentDO agent, HeartbeatReqVO reqVO) {
        XianyuAgentDO update = new XianyuAgentDO();
        update.setId(agent.getId());
        update.setHostName(reqVO.getHostName());
        update.setOsVersion(reqVO.getOsVersion());
        update.setAgentVersion(reqVO.getAgentVersion());
        update.setCapabilities(normalizeCapabilities(reqVO.getCapabilities()));
        update.setScreenLocked(Boolean.TRUE.equals(reqVO.getScreenLocked()));
        update.setLastHeartbeatAt(LocalDateTime.now());
        update.setStatus(ONLINE.getStatus());
        agentMapper.updateById(update);
    }

    /**
     * 能力声明归一：旧 Agent 不传 → text；去空/去重/逗号拼接（15 号文档）。
     */
    private static String normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "text";
        }
        String joined = capabilities.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? "text" : joined;
    }

    private Long countPendingTasks(Long agentId) {
        LambdaQueryWrapperX<XianyuReplyTaskDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(XianyuReplyTaskDO::getAgentId, agentId);
        wrapper.eq(XianyuReplyTaskDO::getStatus, XianyuReplyTaskStatusEnum.PENDING.getStatus());
        return replyTaskMapper.selectCount(wrapper);
    }

    @Override
    public PageResult<XianyuMachineAgentRespVO> getAgentPage(XianyuMachineAgentPageReqVO reqVO) {
        PageResult<XianyuAgentDO> pageResult = agentMapper.selectPage(reqVO);
        return new PageResult<>(convertList(pageResult.getList(), this::convertAgent), pageResult.getTotal());
    }

    private XianyuMachineAgentRespVO convertAgent(XianyuAgentDO agent) {
        XianyuMachineAgentRespVO respVO = new XianyuMachineAgentRespVO();
        respVO.setId(agent.getId());
        respVO.setName(agent.getName());
        respVO.setHostName(agent.getHostName());
        respVO.setStatus(agent.getStatus());
        respVO.setScreenLocked(agent.getScreenLocked());
        respVO.setAgentVersion(agent.getAgentVersion());
        respVO.setLastHeartbeatAt(agent.getLastHeartbeatAt());
        // 绑定账号计数（含未对齐行）。MVP Agent 数少，逐条 COUNT 可接受
        LambdaQueryWrapperX<XianyuAgentAccountDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(XianyuAgentAccountDO::getAgentId, agent.getId());
        respVO.setAccountCount(agentAccountMapper.selectCount(wrapper));
        respVO.setRemark(agent.getRemark());
        respVO.setTokenActivated(agent.getTokenHash() != null);
        return respVO;
    }

    @Override
    public List<XianyuAgentAccountRespVO> getAgentAccounts(Long agentId) {
        List<XianyuAgentAccountDO> bindings = agentAccountMapper.selectByAgentId(agentId);
        return convertList(bindings, this::convertAccount);
    }

    private XianyuAgentAccountRespVO convertAccount(XianyuAgentAccountDO binding) {
        XianyuAgentAccountRespVO respVO = new XianyuAgentAccountRespVO();
        respVO.setAccountId(binding.getAccountId());
        respVO.setAuthorizeId(binding.getAuthorizeId());
        respVO.setUserNick(binding.getUserNick());
        respVO.setLoginStatus(binding.getLoginStatus());
        respVO.setLastReportAt(binding.getLastReportAt());
        respVO.setBound(binding.getAccountId() != null);
        return respVO;
    }

    @Override
    public String resetToken(Long agentId) {
        XianyuAgentDO agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw exception(XIANYU_AGENT_NOT_EXISTS);
        }
        String token = tokenService.issueToken(agentId);
        log.info("[xianyu-agent] 管理端重置 Agent(id={}) 专属 Token（旧 Token 立即失效）", agentId);
        return token;
    }

    @Override
    public PageResult<XianyuAgentEventRespVO> getAgentEvents(Long agentId, XianyuAgentEventPageReqVO reqVO) {
        PageResult<XianyuAgentEventDO> pageResult = eventMapper.selectPage(agentId, reqVO);
        return new PageResult<>(convertList(pageResult.getList(), this::convertEvent), pageResult.getTotal());
    }

    private XianyuAgentEventRespVO convertEvent(XianyuAgentEventDO event) {
        XianyuAgentEventRespVO respVO = new XianyuAgentEventRespVO();
        respVO.setId(event.getId());
        respVO.setEventType(event.getEventType());
        respVO.setLevel(event.getLevel());
        respVO.setContent(event.getContent());
        respVO.setCreateTime(event.getCreateTime());
        return respVO;
    }

}
