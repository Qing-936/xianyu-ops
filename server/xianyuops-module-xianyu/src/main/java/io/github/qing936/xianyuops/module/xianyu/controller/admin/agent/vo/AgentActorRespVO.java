package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import lombok.Data;

import java.util.List;

/**
 * Agent Actor 响应 VO（服务端可信身份，最小字段集）。
 */
@Data
public class AgentActorRespVO {

    /** 用户编号（来自服务端认证上下文，非前端传入） */
    private Long userId;

    /** 登录名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 租户编号（来自认证上下文） */
    private Long tenantId;

    /** 角色标识（role.code） */
    private List<String> roles;

}
