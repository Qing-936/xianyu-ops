package io.github.qing936.xianyuops.module.xianyu.controller.admin.message;

import io.github.qing936.xianyuops.framework.apilog.core.annotation.ApiAccessLog;
import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationListReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationReadReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuMessagePageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuMessageRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.message.XianyuMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 闲鱼消息工作台（02 号文档 §5.1）
 */
@Tag(name = "管理后台 - 闲鱼消息")
@RestController
@RequestMapping("/app/xianyu/message")
@Validated
public class XianyuMessageController {

    @Resource
    private XianyuMessageService messageService;

    @GetMapping("/page")
    @Operation(summary = "获取买家消息分页")
    @PreAuthorize("@ss.hasPermission('app:xianyu-message:query')")
    public CommonResult<PageResult<XianyuMessageRespVO>> page(@Valid XianyuMessagePageReqVO reqVO) {
        return success(messageService.getMessagePage(reqVO));
    }

    @GetMapping("/conversation-list")
    @Operation(summary = "获取会话聚合列表（未绑定账号按昵称快照独立成会话）")
    @PreAuthorize("@ss.hasPermission('app:xianyu-message:query')")
    public CommonResult<List<XianyuConversationRespVO>> conversationList(@Valid XianyuConversationListReqVO reqVO) {
        return success(messageService.getConversationList(reqVO));
    }

    @PutMapping("/read")
    @Operation(summary = "会话标记已读（打开会话时调用），返回置为已读的消息行数")
    @PreAuthorize("@ss.hasPermission('app:xianyu-message:query')")
    public CommonResult<Integer> read(@Valid @RequestBody XianyuConversationReadReqVO reqVO) {
        return success(messageService.markConversationRead(reqVO));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "获取全部会话未读消息总数（全局提醒轮询用，轻量 COUNT）")
    @PreAuthorize("@ss.hasPermission('app:xianyu-message:query')")
    @ApiAccessLog(enable = false) // 前端 10s 轮询，记录日志没有意义（14 号 §5.1）
    public CommonResult<Long> unreadCount() {
        return success(messageService.getUnreadCount());
    }

}
