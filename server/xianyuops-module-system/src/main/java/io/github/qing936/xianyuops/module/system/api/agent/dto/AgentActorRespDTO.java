package io.github.qing936.xianyuops.module.system.api.agent.dto;

import java.util.List;

import lombok.Data;

/**
 * Agent Actor 响应 DTO（最小字段集，不暴露隐私字段）。
 */
@Data
public class AgentActorRespDTO {

    /** 用户编号 */
    private Long userId;

    /** 登录名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 角色标识（role.code，已过滤禁用角色） */
    private List<String> roles;

}
