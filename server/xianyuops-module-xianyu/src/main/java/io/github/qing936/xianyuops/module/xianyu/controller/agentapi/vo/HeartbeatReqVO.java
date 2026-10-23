package io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "闲鱼 Agent - 心跳注册 Request VO")
@Data
public class HeartbeatReqVO {

    @Schema(description = "机器指纹（唯一，首次心跳注册）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "机器指纹不能为空")
    @Size(max = 128, message = "机器指纹不能超过 128 个字符")
    private String machineCode;

    @Schema(description = "主机名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "主机名不能为空")
    @Size(max = 128, message = "主机名不能超过 128 个字符")
    private String hostName;

    @Schema(description = "操作系统版本")
    @Size(max = 128, message = "操作系统版本不能超过 128 个字符")
    private String osVersion;

    @Schema(description = "Agent 程序版本")
    @Size(max = 32, message = "Agent 程序版本不能超过 32 个字符")
    private String agentVersion;

    @Schema(description = "能力声明：text/image/emoji（旧 Agent 不传视为 text）")
    @Size(max = 8, message = "能力声明不能超过 8 项")
    private List<@Size(max = 16, message = "能力项不能超过 16 个字符") String> capabilities;

    @Schema(description = "最近是否锁屏")
    private Boolean screenLocked;

    @Schema(description = "当前登录的闲鱼号列表（本次心跳未出现的绑定行 login_status 置 unknown）")
    @Valid
    private List<AccountItem> accounts;

    @Schema(description = "运行指标（cpuPercent/memPercent 等，仅存档 raw_json 参考）")
    private Object metrics;

    @Schema(description = "闲鱼号快照")
    @Data
    public static class AccountItem {

        @Schema(description = "授权 ID（可空）")
        @Size(max = 64, message = "授权 ID 不能超过 64 个字符")
        private String authorizeId;

        @Schema(description = "闲鱼号昵称（定位闲鱼号）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "闲鱼号昵称不能为空")
        @Size(max = 128, message = "闲鱼号昵称不能超过 128 个字符")
        private String userNick;

        @Schema(description = "登录状态：online/offline/unknown", example = "online")
        private String loginStatus;
    }

}
