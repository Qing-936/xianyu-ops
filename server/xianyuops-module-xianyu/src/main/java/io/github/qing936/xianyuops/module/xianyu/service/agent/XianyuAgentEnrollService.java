package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollCreateRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollRespVO;

/**
 * 闲鱼 Agent 一次性注册码服务（替代原「引导 Token」全局长期密钥）。
 * <p>
 * 注册码只在「首次注册心跳」这一次请求里有效：短时效 + 单次消费 + 明文只下发一次，
 * 泄露面从「整个机群」收敛到「一台机、一个 TTL 窗口」。
 */
public interface XianyuAgentEnrollService {

    /**
     * Filter 调用：注册码精确匹配（SHA-256 查表 + 未使用 + 未过期）。命中返回注册码编号，未命中返回 null。
     * <p>
     * 内部经 TenantUtils 在配置租户上下文中执行（Filter 无租户上下文）。
     */
    Long resolveUsableId(String rawCode);

    /** 心跳注册成功后消费注册码（写 used_time + agent_id，此后该码不再可用）。调用点须在租户上下文内 */
    void consume(Long enrollId, Long agentId);

    /** 管理端：生成注册码（明文仅本次返回，库中只存 SHA-256） */
    XianyuAgentEnrollCreateRespVO createEnrollCode(XianyuAgentEnrollCreateReqVO reqVO);

    /** 管理端：注册码分页（状态派生，已消费的码回填 Agent 名称） */
    PageResult<XianyuAgentEnrollRespVO> getEnrollPage(XianyuAgentEnrollPageReqVO reqVO);

    /** 管理端：撤销注册码（逻辑删除，立即失效；已消费的码撤销不影响已签发的专属 Token） */
    void revokeEnrollCode(Long id);

}
