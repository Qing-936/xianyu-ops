package io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 闲鱼会话聚合列表 Request VO")
@Data
public class XianyuConversationListReqVO {

    @Schema(description = "闲鱼账号 ID（可选筛选，null = 全部）")
    private Long accountId;

    @Schema(description = "账号昵称快照（可选筛选，null = 全部）")
    private String accountNick;

}
