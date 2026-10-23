package io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuEmojiCatalogRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.replytask.XianyuEmojiCatalog;
import io.github.qing936.xianyuops.module.xianyu.service.replytask.XianyuReplyTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 闲鱼回复任务（唯一外发通道，02 号文档 §5.1）
 */
@Tag(name = "管理后台 - 闲鱼回复任务")
@RestController
@RequestMapping("/app/xianyu/reply-task")
@Validated
public class XianyuReplyTaskController {

    @Resource
    private XianyuReplyTaskService replyTaskService;

    @Resource
    private XianyuEmojiCatalog emojiCatalog;

    @PostMapping("/create")
    @Operation(summary = "创建回复任务（外发闸门：账号绑定 → Agent 在线 → 账号登录）")
    @PreAuthorize("@ss.hasPermission('app:xianyu-reply-task:create')")
    public CommonResult<Long> create(@Valid @RequestBody XianyuReplyTaskCreateReqVO reqVO) {
        return success(replyTaskService.create(reqVO, SecurityFrameworkUtils.getLoginUserId()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消 pending 任务（仅 pending 可取消）")
    @Parameter(name = "id", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('app:xianyu-reply-task:cancel')")
    public CommonResult<Boolean> cancel(@PathVariable("id") Long id) {
        replyTaskService.cancel(id);
        return success(true);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个回复任务（发送状态轮询）")
    @Parameter(name = "id", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('app:xianyu-reply-task:query')")
    public CommonResult<XianyuReplyTaskRespVO> get(@PathVariable("id") Long id) {
        return success(replyTaskService.getTask(id));
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "重试失败的回复任务（复制原任务过外发闸门，复用原消息气泡）")
    @Parameter(name = "id", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('app:xianyu-reply-task:create')")
    public CommonResult<Long> retry(@PathVariable("id") Long id) {
        return success(replyTaskService.retry(id, SecurityFrameworkUtils.getLoginUserId()));
    }

    @GetMapping("/page")
    @Operation(summary = "获取回复任务分页")
    @PreAuthorize("@ss.hasPermission('app:xianyu-reply-task:query')")
    public CommonResult<PageResult<XianyuReplyTaskRespVO>> page(@Valid XianyuReplyTaskPageReqVO reqVO) {
        return success(replyTaskService.getTaskPage(reqVO));
    }

    @GetMapping("/emoji-catalog")
    @Operation(summary = "获取闲鱼表情目录（管理端表情选择器）")
    @PreAuthorize("@ss.hasPermission('app:xianyu-reply-task:query')")
    public CommonResult<List<XianyuEmojiCatalogRespVO>> emojiCatalog() {
        return success(emojiCatalog.list());
    }

}
