package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.tenant.core.util.TenantUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollCreateRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentEnrollDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentEnrollMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEnrollStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.framework.common.util.collection.CollectionUtils.convertList;
import static io.github.qing936.xianyuops.framework.common.util.collection.CollectionUtils.convertMap;
import static io.github.qing936.xianyuops.framework.common.util.collection.CollectionUtils.convertSet;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_AGENT_ENROLL_NOT_EXISTS;

/**
 * 闲鱼 Agent 一次性注册码服务实现。
 * <p>
 * 安全约定：注册码明文只在 {@link #createEnrollCode} 的返回体里出现一次，库中只存 SHA-256，
 * 日志与分页返回体均不含明文/哈希。
 */
@Service
@Slf4j
public class XianyuAgentEnrollServiceImpl implements XianyuAgentEnrollService {

    /** 明文前缀：便于人眼在 config.local.yaml / 日志里一眼认出是注册码（不是专属 Token） */
    private static final String CODE_PREFIX = "xyag-";

    /** 随机段字节数（20 字节 = 160 bit，远超暴力猜测可行域） */
    private static final int CODE_RANDOM_BYTES = 20;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private XianyuAgentEnrollMapper enrollMapper;

    @Resource
    private XianyuAgentMapper agentMapper;

    @Resource
    private XianyuAgentProperties properties;

    @Override
    public Long resolveUsableId(String rawCode) {
        String codeHash = XianyuAgentTokenService.sha256Hex(rawCode);
        // DB 异常不吞（冒泡为 5xx）：把服务端故障误判成「注册码无效」会让 Agent 端 401 退出进程
        XianyuAgentEnrollDO enroll = TenantUtils.execute(properties.getTenantId(),
                () -> enrollMapper.selectUsableByCodeHash(codeHash, LocalDateTime.now()));
        return enroll != null ? enroll.getId() : null;
    }

    @Override
    public void consume(Long enrollId, Long agentId) {
        XianyuAgentEnrollDO update = new XianyuAgentEnrollDO();
        update.setId(enrollId);
        update.setUsedTime(LocalDateTime.now());
        update.setAgentId(agentId);
        enrollMapper.updateById(update);
        log.info("[xianyu-agent] 注册码(id={}) 已被 Agent(id={}) 消费", enrollId, agentId);
    }

    @Override
    public XianyuAgentEnrollCreateRespVO createEnrollCode(XianyuAgentEnrollCreateReqVO reqVO) {
        String plainCode = generateEnrollCode();
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(properties.getEnrollCodeTtlMinutes());
        XianyuAgentEnrollDO enroll = new XianyuAgentEnrollDO();
        enroll.setCodeHash(XianyuAgentTokenService.sha256Hex(plainCode));
        enroll.setRemark(reqVO.getRemark());
        enroll.setExpireTime(expireTime);
        enrollMapper.insert(enroll);
        log.info("[xianyu-agent] 生成注册码(id={})，{} 分钟内一次性有效", enroll.getId(), properties.getEnrollCodeTtlMinutes());
        XianyuAgentEnrollCreateRespVO respVO = new XianyuAgentEnrollCreateRespVO();
        respVO.setId(enroll.getId());
        respVO.setCode(plainCode);
        respVO.setRemark(enroll.getRemark());
        respVO.setExpireTime(expireTime);
        return respVO;
    }

    @Override
    public PageResult<XianyuAgentEnrollRespVO> getEnrollPage(XianyuAgentEnrollPageReqVO reqVO) {
        PageResult<XianyuAgentEnrollDO> pageResult = enrollMapper.selectPage(reqVO, LocalDateTime.now());
        // 批量回填 Agent 名称，避免 N+1（convertSet 已过滤 null agentId）
        Set<Long> agentIds = convertSet(pageResult.getList(), XianyuAgentEnrollDO::getAgentId);
        Map<Long, String> agentNames = agentIds.isEmpty() ? Collections.emptyMap()
                : convertMap(agentMapper.selectBatchIds(agentIds), XianyuAgentDO::getId, XianyuAgentDO::getName);
        return new PageResult<>(convertList(pageResult.getList(), enroll -> convert(enroll, agentNames)),
                pageResult.getTotal());
    }

    private XianyuAgentEnrollRespVO convert(XianyuAgentEnrollDO enroll, Map<Long, String> agentNames) {
        XianyuAgentEnrollRespVO respVO = new XianyuAgentEnrollRespVO();
        respVO.setId(enroll.getId());
        respVO.setRemark(enroll.getRemark());
        respVO.setStatus(XianyuAgentEnrollStatusEnum.resolve(enroll.getUsedTime(), enroll.getExpireTime()).getStatus());
        respVO.setExpireTime(enroll.getExpireTime());
        respVO.setUsedTime(enroll.getUsedTime());
        respVO.setAgentId(enroll.getAgentId());
        respVO.setAgentName(enroll.getAgentId() != null ? agentNames.get(enroll.getAgentId()) : null);
        respVO.setCreateTime(enroll.getCreateTime());
        return respVO;
    }

    @Override
    public void revokeEnrollCode(Long id) {
        if (enrollMapper.selectById(id) == null) {
            throw exception(XIANYU_AGENT_ENROLL_NOT_EXISTS);
        }
        enrollMapper.deleteById(id);
        log.info("[xianyu-agent] 撤销注册码(id={})", id);
    }

    /** 生成注册码明文：{@value #CODE_PREFIX} + 40 hex（20 字节 SecureRandom） */
    private static String generateEnrollCode() {
        byte[] bytes = new byte[CODE_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return CODE_PREFIX + HexFormat.of().formatHex(bytes);
    }

}
