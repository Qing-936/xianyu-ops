package io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 闲鱼消息分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuMessagePageReqVO extends PageParam {

    @Schema(description = "来源 Agent")
    private Long agentId;

    @Schema(description = "闲鱼账号 ID")
    private Long accountId;

    @Schema(description = "账号昵称快照，模糊匹配")
    private String accountNick;

    @Schema(description = "买家昵称，模糊匹配")
    private String buyerNick;

    @Schema(description = "消息状态：unread/read/replied")
    private String msgStatus;

    @Schema(description = "方向：in/out")
    private String direction;

    @Schema(description = "会话绑定闲鱼商品 ID（string，13 号 §4.4；非空时精确匹配 item_id，itemUnbound 忽略）")
    @Pattern(regexp = "^\\d*$", message = "itemId 只能为数字字符串")
    private String itemId;

    @Schema(description = "匹配未关联商品会话（item_id IS NULL）；itemId 为空时生效，13 号 §4.4")
    private Boolean itemUnbound;

}
