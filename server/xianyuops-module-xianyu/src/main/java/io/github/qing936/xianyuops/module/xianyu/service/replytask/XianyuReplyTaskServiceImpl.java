package io.github.qing936.xianyuops.module.xianyu.service.replytask;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.PollRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.ResultReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentAccountDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuChatMessageDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuReplyTaskDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentAccountMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuChatMessageMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuReplyTaskMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentStatusEnum;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuContentTypeEnum;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuLoginStatusEnum;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskStatusEnum;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuSendStatusEnum;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentProperties;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.github.qing936.xianyuops.framework.common.util.collection.CollectionUtils.convertList;
import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_AGENT_ACCOUNT_NOT_BOUND;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_AGENT_ACCOUNT_OFFLINE;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_AGENT_OFFLINE;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_CONTENT_EMPTY;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_CONTENT_TOO_LONG;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_CONTENT_TYPE_INVALID;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_MEDIA_DISABLED;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_MEDIA_INVALID;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_MEDIA_UNSUPPORTED;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_NOT_EXISTS;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_REPLY_TASK_STATUS_INVALID;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuMessageDirectionEnum.OUT;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuMsgStatusEnum.READ;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuMsgStatusEnum.REPLIED;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskSourceEnum.MANUAL;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskStatusEnum.DISPATCHED;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskStatusEnum.PENDING;

/**
 * 闲鱼回复任务状态机实现
 * <p>
 * 外发闸门：回复任务创建是唯一外发通道，所有前置校验（账号绑定、账号在线、Agent 在线、内容非空）收口在 {@link #create}。
 */
@Service
@Slf4j
public class XianyuReplyTaskServiceImpl implements XianyuReplyTaskService {

    @Resource
    private XianyuReplyTaskMapper taskMapper;

    @Resource
    private XianyuAgentMapper agentMapper;

    @Resource
    private XianyuAgentAccountMapper agentAccountMapper;

    @Resource
    private XianyuChatMessageMapper messageMapper;

    @Resource
    private XianyuAgentProperties properties;

    @Resource
    private XianyuEmojiCatalog emojiCatalog;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(XianyuReplyTaskCreateReqVO reqVO, Long userId) {
        return doCreate(reqVO, userId, true);
    }

    /**
     * 创建回复任务的共用实现（外发闸门：账号绑定 → Agent 在线 → 账号登录 → 内容校验）。
     * <p>
     * 拆分目的：重试需复制原任务字段重新过闸门，但**不得再插一条气泡**（用户选定「复用同一条气泡」），
     * 故 {@code recordOut=false} 时不落 out 消息，由 {@link #retry} 把原消息行改绑到新任务。
     * 事务注解放在外部入口（create/retry）——同类自调用不走代理，注解在 doCreate 上会失效。
     */
    public Long doCreate(XianyuReplyTaskCreateReqVO reqVO, Long userId, boolean recordOut) {
        // 1. 内容类型/媒体校验（先于账号查询，廉价校验前置）
        String contentTypeText = StringUtils.hasText(reqVO.getContentType())
                ? reqVO.getContentType().trim() : XianyuContentTypeEnum.TEXT.getType();
        XianyuContentTypeEnum contentType = XianyuContentTypeEnum.valueOfType(contentTypeText);
        if (contentType == null || !contentType.isOutboundSupported()) {
            throw exception(XIANYU_REPLY_TASK_CONTENT_TYPE_INVALID);
        }
        if (contentType != XianyuContentTypeEnum.TEXT
                && !Boolean.TRUE.equals(properties.getOutboundMediaEnabled())) {
            throw exception(XIANYU_REPLY_TASK_MEDIA_DISABLED);
        }
        String content = reqVO.getContent();
        String extJson = null;
        if (contentType == XianyuContentTypeEnum.IMAGE || contentType == XianyuContentTypeEnum.EMOJI) {
            XianyuOutboundMediaNormalizer.Media media;
            try {
                media = XianyuOutboundMediaNormalizer.normalize(contentType.getType(), reqVO.getMedia());
            } catch (IllegalArgumentException e) {
                log.warn("[xianyu-reply] 媒体校验失败: {}", e.getMessage());
                throw exception(XIANYU_REPLY_TASK_MEDIA_INVALID);
            }
            if (contentType == XianyuContentTypeEnum.EMOJI && !emojiCatalog.containsAlt(media.alt())) {
                throw exception(XIANYU_REPLY_TASK_MEDIA_INVALID);
            }
            content = contentType == XianyuContentTypeEnum.IMAGE ? "[图片]" : media.alt();
            extJson = XianyuOutboundMediaNormalizer.toExtJson(media);
        }
        if (!StringUtils.hasText(content)) {
            throw exception(XIANYU_REPLY_TASK_CONTENT_EMPTY);
        }
        if (content.length() > 5000) {
            throw exception(XIANYU_REPLY_TASK_CONTENT_TOO_LONG);
        }
        // 2. 账号绑定：存在该 accountId 的绑定行（§5.2-⑥）
        List<XianyuAgentAccountDO> bindings = agentAccountMapper.selectListByAccountId(reqVO.getAccountId());
        if (bindings.isEmpty()) {
            // 无任何绑定行 → 账号未绑定（M1 评审 P3-1：与「有绑定但 Agent 离线」区分错误码语义）
            throw exception(XIANYU_AGENT_ACCOUNT_NOT_BOUND);
        }
        XianyuAgentAccountDO binding = findOnlineBinding(bindings);
        if (binding == null) {
            // 有绑定行但绑定 Agent 均离线（findOnlineBinding 已过滤在线 Agent）
            throw exception(XIANYU_AGENT_OFFLINE);
        }
        // 3. Agent 在线校验（findOnlineBinding 已过滤；此处防御性再查 agent 行）
        XianyuAgentDO agent = agentMapper.selectById(binding.getAgentId());
        if (agent == null || !XianyuAgentStatusEnum.ONLINE.getStatus().equals(agent.getStatus())) {
            throw exception(XIANYU_AGENT_OFFLINE);
        }
        // 3.5 能力闸门：旧 Agent（capabilities 为空/text）不得接媒体任务，避免把占位文本当正文发出
        if (contentType != XianyuContentTypeEnum.TEXT
                && !supportsCapability(agent.getCapabilities(), contentType)) {
            throw exception(XIANYU_REPLY_TASK_MEDIA_UNSUPPORTED);
        }
        // 4. 账号登录校验
        if (!XianyuLoginStatusEnum.ONLINE.getStatus().equals(binding.getLoginStatus())) {
            throw exception(XIANYU_AGENT_ACCOUNT_OFFLINE);
        }
        // 5. insert（status=pending, source=manual）
        XianyuReplyTaskDO task = new XianyuReplyTaskDO();
        task.setAgentId(binding.getAgentId());
        task.setAccountId(reqVO.getAccountId());
        task.setBuyerNick(reqVO.getBuyerNick());
        task.setMessageId(reqVO.getMessageId());
        task.setItemId(reqVO.getItemId());
        // 会话唯一标识随任务落库：out 消息 chat_id 由此同源（否则会话分组分裂）
        task.setChatId(trimToNull(reqVO.getChatId()));
        task.setContent(content);
        task.setContentType(contentType.getType());
        task.setExtJson(extJson);
        task.setSource(MANUAL.getSource());
        task.setStatus(PENDING.getStatus());
        task.setCreatedBy(userId);
        taskMapper.insert(task);
        // 6. out 消息「创建即落库」（send_status=sending）：发送中状态在聊天历史内立即可见，
        //    终态由 reportResult / Job 超时兜底 / cancel 按 task_id 原地回填
        if (recordOut) {
            insertOutMessage(task, XianyuSendStatusEnum.SENDING, null);
        }
        return task.getId();
    }

    private static boolean supportsCapability(String capabilities, XianyuContentTypeEnum contentType) {
        if (contentType == XianyuContentTypeEnum.TEXT) {
            return true;
        }
        if (!StringUtils.hasText(capabilities)) {
            return false;
        }
        return Arrays.stream(capabilities.split(","))
                .map(String::trim)
                .anyMatch(contentType.getType()::equals);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 绑定行中选出一条「Agent 在线」的（MVP 前提唯一 Agent；多 Agent 绑定同一账号时取第一条并告警）。
     */
    private XianyuAgentAccountDO findOnlineBinding(List<XianyuAgentAccountDO> bindings) {
        List<XianyuAgentAccountDO> onlineBindings = bindings.stream().filter(binding -> {
            XianyuAgentDO agent = agentMapper.selectById(binding.getAgentId());
            return agent != null && XianyuAgentStatusEnum.ONLINE.getStatus().equals(agent.getStatus());
        }).toList();
        if (onlineBindings.isEmpty()) {
            return null;
        }
        if (onlineBindings.size() > 1) {
            log.warn("[xianyu-agent] 账号 {} 被多个在线 Agent 绑定，取第一条: {}", onlineBindings.get(0).getAccountId(),
                    onlineBindings.stream().map(XianyuAgentAccountDO::getAgentId).toList());
        }
        return onlineBindings.get(0);
    }

    @Override
    public PollRespVO pollTasks(Long agentId) {
        long deadline = System.currentTimeMillis() + properties.getLongPollTimeoutSec() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            // 查 pending 任务（LIMIT 20）
            LambdaQueryWrapperX<XianyuReplyTaskDO> wrapper = new LambdaQueryWrapperX<>();
            wrapper.eq(XianyuReplyTaskDO::getAgentId, agentId);
            wrapper.eq(XianyuReplyTaskDO::getStatus, PENDING.getStatus());
            wrapper.orderByAsc(XianyuReplyTaskDO::getId);
            wrapper.last("LIMIT 20");
            List<XianyuReplyTaskDO> pendingTasks = taskMapper.selectList(wrapper);
            // 逐条原子领取（条件更新 pending → dispatched；影响行数=0 → 已被取消/领取，跳过）
            List<PollRespVO.TaskItem> claimed = new ArrayList<>();
            for (XianyuReplyTaskDO task : pendingTasks) {
                if (taskMapper.claimPending(task.getId(), agentId) == 0) {
                    continue;
                }
                claimed.add(buildTaskItem(task));
            }
            if (!claimed.isEmpty()) {
                PollRespVO respVO = new PollRespVO();
                respVO.setTasks(claimed);
                return respVO;
            }
            // 无任务：sleep 1s 后继续（长轮询）
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 挂起超时：返回空 tasks
        PollRespVO respVO = new PollRespVO();
        respVO.setTasks(new ArrayList<>());
        return respVO;
    }

    private PollRespVO.TaskItem buildTaskItem(XianyuReplyTaskDO task) {
        PollRespVO.TaskItem item = new PollRespVO.TaskItem();
        item.setTaskId(task.getId());
        // accountKey：绑定行快照优先，缺失回退主数据
        UploadReqVO.AccountKeyItem accountKey = new UploadReqVO.AccountKeyItem();
        XianyuAgentAccountDO binding = agentAccountMapper.selectByAgentIdAndAccountId(task.getAgentId(), task.getAccountId());
        if (binding != null) {
            accountKey.setUserNick(binding.getUserNick());
            accountKey.setAuthorizeId(binding.getAuthorizeId());
        }
        item.setAccountKey(accountKey);
        item.setBuyerNick(task.getBuyerNick());
        item.setContent(task.getContent());
        item.setContentType(StringUtils.hasText(task.getContentType())
                ? task.getContentType() : XianyuContentTypeEnum.TEXT.getType());
        XianyuOutboundMediaNormalizer.Media media = XianyuOutboundMediaNormalizer.fromExtJson(task.getExtJson());
        if (media != null) {
            PollRespVO.TaskItem.MediaItem mediaItem = new PollRespVO.TaskItem.MediaItem();
            mediaItem.setType(media.type());
            mediaItem.setUrl(media.url());
            mediaItem.setAlt(media.alt());
            item.setMedia(mediaItem);
        }
        // itemId 协议 string 可空（13 号 §4.3）：Agent 弱校验定位入参，未绑定商品会话不传
        item.setItemId(task.getItemId() != null ? String.valueOf(task.getItemId()) : null);
        // 会话唯一标识随任务下发：Agent 侧可用它精确定位会话（优于昵称+商品弱校验），可选使用
        item.setChatId(task.getChatId());
        // knownItems（13 号 §五 增强 B）：目标商品卡常因不在会话可视窗口漏读（同买家多商品会话），
        // 下发该账号×买家会话库中出现过的商品集合，供 Agent 兜底放行，避免旧商品回复被误拒。
        if (task.getItemId() != null && task.getAccountId() != null) {
            List<Long> known = messageMapper.selectKnownItemIds(task.getAccountId(), task.getBuyerNick());
            item.setKnownItems(convertList(known, String::valueOf));
        }
        item.setSource(task.getSource());
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportResult(Long taskId, Long agentId, ResultReqVO reqVO) {
        // 1. 校验任务存在 + 归属 + dispatched 状态
        XianyuReplyTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw exception(XIANYU_REPLY_TASK_NOT_EXISTS);
        }
        if (!agentId.equals(task.getAgentId()) || !DISPATCHED.getStatus().equals(task.getStatus())) {
            throw exception(XIANYU_REPLY_TASK_STATUS_INVALID);
        }
        // 2. 状态白名单（M1 评审 P2-1：仅接受 success/failed；任意持 token 者传 pending 会让任务回
        //    pending → poll 重新领取 → 重复发送，传垃圾字符串则污染状态机）
        XianyuReplyTaskStatusEnum resultStatus = XianyuReplyTaskStatusEnum.valueOfStatus(reqVO.getStatus());
        if (resultStatus != XianyuReplyTaskStatusEnum.SUCCESS && resultStatus != XianyuReplyTaskStatusEnum.FAILED) {
            throw exception(XIANYU_REPLY_TASK_STATUS_INVALID);
        }
        // 3. 更新状态
        XianyuReplyTaskDO update = new XianyuReplyTaskDO();
        update.setId(taskId);
        update.setStatus(resultStatus.getStatus());
        update.setResultMsg(reqVO.getResultMsg());
        update.setVerifyImageUrl(reqVO.getVerifyImageUrl());
        update.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(update);
        // 4. 回填 out 消息发送状态（聊天历史唯一真源：成功/失败都在历史里留痕）
        boolean success = resultStatus == XianyuReplyTaskStatusEnum.SUCCESS;
        int updated = updateOutMessageStatus(taskId,
                success ? XianyuSendStatusEnum.SENT : XianyuSendStatusEnum.FAILED, reqVO.getResultMsg());
        if (updated == 0 && success) {
            // 影响行数 = 0：out 消息行缺失（本特性上线前创建的老任务）→ 成功时补插，避免聊天历史丢消息
            insertOutMessage(task, XianyuSendStatusEnum.SENT, null);
        }
        // 5. success 时置原消息 replied（回复闭环，评审建议采纳项）
        if (success && task.getMessageId() != null) {
            XianyuChatMessageDO messageUpdate = new XianyuChatMessageDO();
            messageUpdate.setId(task.getMessageId());
            messageUpdate.setMsgStatus(REPLIED.getStatus());
            messageMapper.updateById(messageUpdate);
        }
    }

    /**
     * 插入 out 消息（聊天历史唯一真源：创建时 sending、老任务成功兜底补插时 sent）。
     * <p>
     * 三个必须遵守的口径：
     * ① chat_id 取任务快照（与 in 消息同源）——为空会掉进 'noncid:' 分组，与 in 消息的会话分裂成两行；
     * ② msg_status 恒为 read——左栏未读数按 msg_status='unread' 统计，写 unread 会自增红点；
     * ③ receive_time 取**任务创建时刻**（管理端发起 / 回复决策时刻），不用结果回传时刻：
     *    结果回传含发送后验证截图上传（OSS）等耗时（实测晚 40s+），会使 out 排到买家回复
     *    之后，导致会话时间线倒挂（实测：我方 10:40:42 vs 买家 10:39:59）。
     */
    private void insertOutMessage(XianyuReplyTaskDO task, XianyuSendStatusEnum sendStatus, String failReason) {
        XianyuChatMessageDO message = new XianyuChatMessageDO();
        message.setAgentId(task.getAgentId());
        message.setAccountId(task.getAccountId());
        message.setAccountNick(resolveAccountNick(task.getAgentId(), task.getAccountId()));
        message.setBuyerNick(task.getBuyerNick());
        message.setChatId(task.getChatId());
        message.setItemId(task.getItemId());
        message.setDirection(OUT.getDirection());
        message.setContentType(StringUtils.hasText(task.getContentType())
                ? task.getContentType() : XianyuContentTypeEnum.TEXT.getType());
        message.setContent(task.getContent());
        message.setExtJson(task.getExtJson());
        // task_id 是发送状态回填的唯一锚点（reportResult / Job 兜底 / retry 均按此列定位）
        message.setTaskId(task.getId());
        message.setReceiveTime(task.getCreateTime() != null ? task.getCreateTime() : LocalDateTime.now());
        message.setMsgStatus(READ.getStatus());
        message.setSendStatus(sendStatus.getStatus());
        message.setFailReason(failReason != null ? failReason : "");
        messageMapper.insert(message);
    }

    /**
     * 按 task_id 回填 out 消息的发送状态与原因（发送中 → 终态）。
     *
     * @return 影响行数；0 表示 out 消息行缺失（本特性上线前创建的老任务）
     */
    private int updateOutMessageStatus(Long taskId, XianyuSendStatusEnum sendStatus, String failReason) {
        return messageMapper.update(null, new LambdaUpdateWrapper<XianyuChatMessageDO>()
                .eq(XianyuChatMessageDO::getTaskId, taskId)
                .set(XianyuChatMessageDO::getSendStatus, sendStatus.getStatus())
                .set(XianyuChatMessageDO::getFailReason, failReason != null ? failReason : ""));
    }

    /**
     * 账号昵称快照：绑定行 user_nick 优先，回退主数据（绑定行可能已被删除）。
     */
    private String resolveAccountNick(Long agentId, Long accountId) {
        XianyuAgentAccountDO binding = agentAccountMapper.selectByAgentIdAndAccountId(agentId, accountId);
        if (binding != null && StringUtils.hasText(binding.getUserNick())) {
            return binding.getUserNick();
        }
        return binding != null ? binding.getUserNick() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long taskId) {
        // 条件更新（M1 评审 P2-2）：与 poll 原子领取互斥——任务已被领取为 dispatched 时不能改回
        // canceled，否则管理端看到「已取消」而 Agent 已发出
        if (taskMapper.cancelPending(taskId) == 0) {
            // 影响行数 = 0：任务不存在（NOT_EXISTS）或已非 pending（已被领取/取消/终结 → STATUS_INVALID）
            if (taskMapper.selectById(taskId) == null) {
                throw exception(XIANYU_REPLY_TASK_NOT_EXISTS);
            }
            throw exception(XIANYU_REPLY_TASK_STATUS_INVALID);
        }
        // 取消即未发送：同步回填 out 消息，否则气泡永远停在「发送中」
        updateOutMessageStatus(taskId, XianyuSendStatusEnum.FAILED, "任务已取消（未发送）");
    }

    @Override
    public XianyuReplyTaskRespVO getTask(Long taskId) {
        XianyuReplyTaskDO task = taskMapper.selectById(taskId);
        return task == null ? null : convertTask(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long retry(Long taskId, Long userId) {
        // 1. 原任务存在，且已终态失败/超时（成功或进行中不允许重试，防重复外发）
        XianyuReplyTaskDO origin = taskMapper.selectById(taskId);
        if (origin == null) {
            throw exception(XIANYU_REPLY_TASK_NOT_EXISTS);
        }
        XianyuReplyTaskStatusEnum originStatus = XianyuReplyTaskStatusEnum.valueOfStatus(origin.getStatus());
        if (originStatus != XianyuReplyTaskStatusEnum.FAILED && originStatus != XianyuReplyTaskStatusEnum.TIMEOUT) {
            throw exception(XIANYU_REPLY_TASK_STATUS_INVALID);
        }
        // 2. 复制原任务关键字段，走同一套外发闸门新建任务（recordOut=false：不插新气泡）
        XianyuReplyTaskCreateReqVO reqVO = new XianyuReplyTaskCreateReqVO();
        reqVO.setAccountId(origin.getAccountId());
        reqVO.setBuyerNick(origin.getBuyerNick());
        reqVO.setMessageId(origin.getMessageId());
        reqVO.setItemId(origin.getItemId());
        reqVO.setChatId(origin.getChatId());
        reqVO.setContent(origin.getContent());
        reqVO.setContentType(origin.getContentType());
        XianyuOutboundMediaNormalizer.Media media = XianyuOutboundMediaNormalizer.fromExtJson(origin.getExtJson());
        if (media != null) {
            XianyuReplyTaskCreateReqVO.MediaVO mediaVO = new XianyuReplyTaskCreateReqVO.MediaVO();
            mediaVO.setType(media.type());
            mediaVO.setUrl(media.url());
            mediaVO.setAlt(media.alt());
            reqVO.setMedia(mediaVO);
        }
        Long newTaskId = doCreate(reqVO, userId, false);
        // 3. 复用同一条气泡：原 out 消息行改绑新任务 + 重置为发送中（receive_time 刷新为本次重试时刻）
        int rebound = messageMapper.update(null, new LambdaUpdateWrapper<XianyuChatMessageDO>()
                .eq(XianyuChatMessageDO::getTaskId, taskId)
                .set(XianyuChatMessageDO::getTaskId, newTaskId)
                .set(XianyuChatMessageDO::getSendStatus, XianyuSendStatusEnum.SENDING.getStatus())
                .set(XianyuChatMessageDO::getFailReason, "")
                .set(XianyuChatMessageDO::getReceiveTime, LocalDateTime.now()));
        if (rebound == 0) {
            // 原消息行缺失（本特性上线前失败未落库的历史任务）→ 补插一条，保证重试也有气泡可跟踪
            log.warn("[xianyu-agent] 重试未找到原 out 消息行，补插: taskId={} newTaskId={}", taskId, newTaskId);
            insertOutMessage(taskMapper.selectById(newTaskId), XianyuSendStatusEnum.SENDING, null);
        }
        return newTaskId;
    }

    @Override
    public PageResult<XianyuReplyTaskRespVO> getTaskPage(XianyuReplyTaskPageReqVO reqVO) {
        PageResult<XianyuReplyTaskDO> pageResult = taskMapper.selectPage(reqVO);
        return new PageResult<>(convertList(pageResult.getList(), this::convertTask), pageResult.getTotal());
    }

    private XianyuReplyTaskRespVO convertTask(XianyuReplyTaskDO task) {
        XianyuReplyTaskRespVO respVO = new XianyuReplyTaskRespVO();
        respVO.setId(task.getId());
        respVO.setAgentId(task.getAgentId());
        respVO.setAccountId(task.getAccountId());
        respVO.setBuyerNick(task.getBuyerNick());
        respVO.setItemId(task.getItemId());
        respVO.setChatId(task.getChatId());
        respVO.setContent(task.getContent());
        respVO.setContentType(StringUtils.hasText(task.getContentType())
                ? task.getContentType() : XianyuContentTypeEnum.TEXT.getType());
        respVO.setSource(task.getSource());
        respVO.setStatus(task.getStatus());
        respVO.setResultMsg(task.getResultMsg());
        respVO.setVerifyImageUrl(task.getVerifyImageUrl());
        respVO.setCreatedBy(task.getCreatedBy());
        respVO.setCreateTime(task.getCreateTime());
        respVO.setDispatchedAt(task.getDispatchedAt());
        respVO.setFinishedAt(task.getFinishedAt());
        return respVO;
    }

}
