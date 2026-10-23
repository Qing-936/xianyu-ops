package io.github.qing936.xianyuops.module.xianyu.service.message;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationListReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationReadReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuMessagePageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuMessageRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuChatMessageDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuChatMessageMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuContentTypeEnum;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuMessageDirectionEnum;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuMsgStatusEnum;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentAccountService;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.framework.common.util.collection.CollectionUtils.convertList;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_MESSAGE_UPLOAD_EMPTY;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuContentTypeEnum.TEXT;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuMessageDirectionEnum.IN;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuMsgStatusEnum.UNREAD;

/**
 * 闲鱼买家消息服务实现
 * <p>
 * 判重策略（评审 P1-R2）：无永久唯一键，纯窗口判重（先查再插，5 分钟窗口），
 * 判重维度含账号昵称快照，防两个未绑定账号的同一买家同内容误判重复。
 */
@Service
@Slf4j
public class XianyuMessageServiceImpl implements XianyuMessageService {

    // 媒体可信域名：阿里系 CDN + 闲鱼/本项目域名 + 钉钉 IM 媒体 CDN
    // （与出站共用 XianyuMediaHosts，避免两套白名单漂移；出站强制 HTTPS，入站仍容忍 http→https，
    //  差异见 XianyuOutboundMediaNormalizer 类注释）
    private static final List<String> MEDIA_HOSTS = XianyuMediaHosts.HOSTS;
    private static final int MEDIA_ITEMS_MAX = 20;
    private static final int MEDIA_URL_MAX_LENGTH = 4096;
    private static final BigDecimal MEDIA_DURATION_MAX = BigDecimal.valueOf(86400);
    /** ext_json 列为 TEXT（65535 字节），留余量拒绝对端塞入超大媒体快照 */
    private static final int MEDIA_EXT_MAX_BYTES = 60000;

    @Resource
    private XianyuChatMessageMapper messageMapper;

    @Resource
    private XianyuAgentAccountService accountService;

    @Resource
    private XianyuAgentProperties properties;

    @Override
    public UploadRespVO upload(Long agentId, List<UploadReqVO.MessageItem> messages) {
        // 防御性兜底：Controller 层 @Valid 已拦截空列表，此处保证错误码 XIANYU_MESSAGE_UPLOAD_EMPTY 生效
        if (messages == null || messages.isEmpty()) {
            throw exception(XIANYU_MESSAGE_UPLOAD_EMPTY);
        }
        int accepted = 0;
        int duplicate = 0;
        List<UploadRespVO.FailureItem> failures = new ArrayList<>();
        // 单条 try/catch 隔离，任何异常不中断整批
        for (UploadReqVO.MessageItem item : messages) {
            try {
                UploadResult result = uploadOne(agentId, item);
                if (result == UploadResult.ACCEPTED) {
                    accepted++;
                } else if (result == UploadResult.DUPLICATE) {
                    duplicate++;
                }
            } catch (Exception e) {
                log.warn("[xianyu-agent] 消息上报单条失败: clientMsgId={}", item.getClientMsgId(), e);
                UploadRespVO.FailureItem failure = new UploadRespVO.FailureItem();
                failure.setClientMsgId(item.getClientMsgId());
                failure.setReason(e.getMessage() != null ? e.getMessage() : "未知错误");
                failures.add(failure);
            }
        }
        UploadRespVO respVO = new UploadRespVO();
        respVO.setAcceptedCount(accepted);
        respVO.setDuplicateCount(duplicate);
        respVO.setFailedCount(failures.size());
        respVO.setFailures(failures);
        return respVO;
    }

    private UploadResult uploadOne(Long agentId, UploadReqVO.MessageItem item) {
        // 1. 方向校验：契约 direction 可选默认 in（02 §4.2）；MVP 仅接受 in，out 由 Java 补录（§6.5）
        XianyuMessageDirectionEnum direction;
        if (!StringUtils.hasText(item.getDirection())) {
            direction = IN; // null/blank 视为 in（M1 评审 P2-3：契约默认可省略）
        } else {
            direction = XianyuMessageDirectionEnum.valueOfDirection(item.getDirection());
            if (direction == null) {
                throw new IllegalArgumentException("direction 仅支持 in/out，收到: " + item.getDirection());
            }
        }
        if (direction != IN) {
            throw new IllegalArgumentException("MVP 仅接受 direction=in，out 由服务端补录");
        }
        XianyuContentTypeEnum contentType = item.getContentType() == null
                ? TEXT : XianyuContentTypeEnum.valueOfType(item.getContentType());
        if (contentType == null) {
            throw new IllegalArgumentException("contentType 不支持");
        }
        String contentTypeStr = contentType.getType();
        String content = item.getContent() == null ? "" : item.getContent();
        Map<String, Object> ext = item.getExt();
        String mediaKey = null;
        if (contentType.isMedia()) {
            ext = normalizeMediaExt(contentType, ext);
            mediaKey = (String) ext.get("dedupKey");
            if (!StringUtils.hasText(content)) {
                content = "[" + contentType.getDesc() + "]";
            }
        } else if (contentType != XianyuContentTypeEnum.CARD && !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("contentType=" + contentTypeStr + " 消息内容不能为空");
        }
        // 4. itemId 解析：协议 string 可空 → Long（13 号 D-13-2）
        Long itemId = parseItemId(item.getItemId());
        // 4.1 会话唯一 id 归一（2026-09-24 会话唯一性）：空白串归一 null，避免 '' 与 NULL 落成两种会话键
        String chatId = trimToNull(item.getChatId());
        // 5. 窗口判重（item + 会话维度，13 号 D-13-5）：命中 → duplicate
        LocalDateTime receiveTime = parseReceiveTime(item.getReceiveTime());
        LocalDateTime windowStart = receiveTime.minusMinutes(properties.getMsgDedupWindowMinutes());
        Long dupId = messageMapper.selectDedupHit(agentId, item.getAccountKey().getUserNick(),
                item.getBuyerNick(), content, contentTypeStr, mediaKey, itemId, chatId, windowStart);
        if (dupId != null) {
            return UploadResult.DUPLICATE;
        }
        // 6. 账号对齐（可空，未对齐也入库，account_nick 快照兜底）
        Long accountId = accountService.resolveAccountId(
                item.getAccountKey().getAuthorizeId(), item.getAccountKey().getUserNick());
        // 7. insert
        XianyuChatMessageDO message = new XianyuChatMessageDO();
        message.setAgentId(agentId);
        message.setAccountId(accountId);
        message.setAccountNick(item.getAccountKey().getUserNick());
        message.setBuyerId(item.getBuyerId());
        message.setBuyerNick(item.getBuyerNick());
        message.setChatId(chatId);
        message.setItemId(itemId);
        message.setContentType(contentTypeStr);
        message.setContent(content);
        message.setDirection(IN.getDirection());
        message.setReceiveTime(receiveTime);
        message.setMsgStatus(UNREAD.getStatus());
        message.setExtJson(ext != null ? JsonUtils.toJsonString(ext) : null);
        // 会话级商品快照（方案 A，2026-09-24）：图取会话行右侧商品缩略图、价取会话内最近商品卡价格文本；
        // 与 itemId 绑定无关（每条消息都带），供管理端左栏/头部展示——item_id 为 NULL 的会话也能看到商品
        message.setProductImageUrl(trimToNull(item.getProductImageUrl()));
        message.setProductPriceText(trimToNull(item.getProductPriceText()));
        // 会话商品标题（2026-09-24）：取会话行 itemInfo.title，商品卡 ext 兜底时回填
        message.setProductTitle(trimToNull(item.getProductTitle()));
        messageMapper.insert(message);
        return UploadResult.ACCEPTED;
    }

    private Map<String, Object> normalizeMediaExt(XianyuContentTypeEnum contentType, Map<String, Object> rawExt) {
        if (rawExt == null || !"media".equals(rawExt.get("type"))) {
            throw new IllegalArgumentException("媒体 ext.type 必须为 media");
        }
        if (!(rawExt.get("items") instanceof List<?> rawItems)
                || rawItems.isEmpty() || rawItems.size() > MEDIA_ITEMS_MAX) {
            throw new IllegalArgumentException("媒体 ext.items 必须为 1~20 项数组");
        }
        String sourceMessageId = mediaString(rawExt.get("sourceMessageId"), "sourceMessageId", 128);
        List<Map<String, Object>> items = new ArrayList<>(rawItems.size());
        List<Map<String, Object>> dedupItems = new ArrayList<>(rawItems.size());
        for (Object rawItem : rawItems) {
            Map<String, Object> mediaItem = normalizeMediaItem(rawItem);
            items.add(mediaItem);
            Map<String, Object> dedupItem = new LinkedHashMap<>();
            for (String field : List.of("type", "url", "alt", "unavailableReason")) {
                if (mediaItem.containsKey(field)) {
                    dedupItem.put(field, mediaItem.get(field));
                }
            }
            dedupItems.add(dedupItem);
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", contentType.getType());
        if (sourceMessageId != null) {
            identity.put("sourceMessageId", sourceMessageId);
        } else {
            identity.put("items", dedupItems);
        }
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("type", "media");
        if (sourceMessageId != null) {
            ext.put("sourceMessageId", sourceMessageId);
        }
        ext.put("items", items);
        ext.put("dedupKey", DigestUtil.sha256Hex(JsonUtils.toJsonString(identity)));
        // 落库列 ext_json 为 TEXT（65535 字节），超限会让整条插入报数据库错误；显式拒绝给出可读原因
        String extJson = JsonUtils.toJsonString(ext);
        if (extJson.getBytes(StandardCharsets.UTF_8).length > MEDIA_EXT_MAX_BYTES) {
            throw new IllegalArgumentException("媒体 ext_json 超过 " + MEDIA_EXT_MAX_BYTES + " 字节上限（TEXT 列容量）");
        }
        return ext;
    }

    private Map<String, Object> normalizeMediaItem(Object rawItem) {
        if (!(rawItem instanceof Map<?, ?> fields) || !(fields.get("type") instanceof String type)) {
            throw new IllegalArgumentException("媒体 items 条目必须为对象且 type 必须为字符串");
        }
        XianyuContentTypeEnum itemType = XianyuContentTypeEnum.valueOfType(type);
        if (itemType == null || !itemType.isMedia()) {
            throw new IllegalArgumentException("媒体 items.type 仅支持 image/emoji/audio/video");
        }
        String url = normalizeMediaUrl(fields.get("url"), "url");
        String posterUrl = normalizeMediaUrl(fields.get("posterUrl"), "posterUrl");
        String alt = mediaString(fields.get("alt"), "alt", 500);
        String reason = mediaString(fields.get("unavailableReason"), "unavailableReason", 200);
        if (url == null && reason == null && !(itemType == XianyuContentTypeEnum.EMOJI && alt != null)) {
            throw new IllegalArgumentException("媒体无 url 时必须提供 unavailableReason；emoji 可用 alt 表达");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", itemType.getType());
        item.put("url", url);
        item.put("posterUrl", posterUrl);
        item.put("alt", alt);
        item.put("duration", normalizeMediaDuration(fields.get("duration")));
        item.put("unavailableReason", reason);
        item.values().removeIf(Objects::isNull);
        return item;
    }

    private String mediaString(Object value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > maxLength) {
            throw new IllegalArgumentException("媒体 " + field + " 必须为不超过 " + maxLength + " 字符的字符串");
        }
        return StrUtil.trimToNull(text);
    }

    private BigDecimal normalizeMediaDuration(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("媒体 duration 必须为 0~86400 的有限数值（秒）");
        }
        BigDecimal duration;
        try {
            duration = new BigDecimal(number.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("媒体 duration 必须为 0~86400 的有限数值（秒）");
        }
        if (duration.signum() < 0 || duration.compareTo(MEDIA_DURATION_MAX) > 0) {
            throw new IllegalArgumentException("媒体 duration 必须为 0~86400 的有限数值（秒）");
        }
        return duration.stripTrailingZeros();
    }

    private String normalizeMediaUrl(Object value, String field) {
        if (value instanceof String raw && hasMediaUrlControl(raw)) {
            throw new IllegalArgumentException("媒体 " + field + " 禁止包含控制符");
        }
        String url = mediaString(value, field, MEDIA_URL_MAX_LENGTH);
        if (url == null) {
            return null;
        }
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme);
            boolean https = "https".equalsIgnoreCase(scheme);
            String host = uri.getHost();
            int port = uri.getPort();
            if ((!http && !https) || host == null || uri.getRawUserInfo() != null
                    || (port != -1 && port != (https ? 443 : 80))
                    || !uri.getRawAuthority().equalsIgnoreCase(host + (port == -1 ? "" : ":" + port))
                    || hasMediaUrlControl(uri.getSchemeSpecificPart()) || hasMediaUrlControl(uri.getFragment())) {
                throw new IllegalArgumentException("媒体 " + field + " 必须为无用户信息、无控制符且使用默认端口的 HTTP(S) URL");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (MEDIA_HOSTS.stream().noneMatch(domain -> normalizedHost.equals(domain)
                    || normalizedHost.endsWith("." + domain))) {
                throw new IllegalArgumentException("媒体 " + field + " 域名不在可信媒体白名单内");
            }
            String path = StrUtil.isEmpty(uri.getRawPath()) ? "/" : uri.getRawPath();
            String normalized = new URI("https://" + normalizedHost + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment())).toASCIIString();
            if (normalized.length() > MEDIA_URL_MAX_LENGTH) {
                throw new IllegalArgumentException("媒体 " + field + " 规范化后不能超过 4096 字符");
            }
            return normalized;
        } catch (URISyntaxException e) {
            // 不透出 URI 解析异常，避免签名链接进入日志或响应。
            throw new IllegalArgumentException("媒体 " + field + " URL 格式不合法");
        }
    }

    private boolean hasMediaUrlControl(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }

    /** 去除首尾空白；空白串归一为 null（防空串污染商品快照回填的 IS NOT NULL 判据）。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * itemId string → Long（13 号 D-13-2：协议写死 string 规避 JS 精度风险）；非法值计 failed。
     */
    private Long parseItemId(String itemId) {
        if (!StringUtils.hasText(itemId)) {
            return null;
        }
        try {
            return Long.valueOf(itemId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("itemId 解析失败: " + itemId);
        }
    }

    /**
     * receiveTime ISO-8601 解析（契约时间字段为 String——框架 Jackson 将 LocalDateTime 序列化为 EpochMillis，
     * 与 Agent 契约冲突）。解析失败抛异常 → 计 failed。
     */
    private LocalDateTime parseReceiveTime(String receiveTime) {
        try {
            return LocalDateTime.parse(receiveTime);
        } catch (Exception e) {
            throw new IllegalArgumentException("receiveTime 解析失败: " + receiveTime);
        }
    }

    private enum UploadResult {
        ACCEPTED, DUPLICATE
    }

    @Override
    public PageResult<XianyuMessageRespVO> getMessagePage(XianyuMessagePageReqVO reqVO) {
        PageResult<XianyuChatMessageDO> pageResult = messageMapper.selectPage(reqVO);
        return new PageResult<>(convertList(pageResult.getList(), this::convertMessage), pageResult.getTotal());
    }

    private XianyuMessageRespVO convertMessage(XianyuChatMessageDO message) {
        XianyuMessageRespVO respVO = new XianyuMessageRespVO();
        respVO.setId(message.getId());
        respVO.setAccountId(message.getAccountId());
        respVO.setAccountNick(message.getAccountNick());
        respVO.setBuyerNick(message.getBuyerNick());
        respVO.setItemId(message.getItemId());
        respVO.setDirection(message.getDirection());
        respVO.setContentType(message.getContentType());
        respVO.setContent(message.getContent());
        respVO.setExtJson(message.getExtJson());
        respVO.setReceiveTime(message.getReceiveTime());
        respVO.setMsgStatus(message.getMsgStatus());
        // out 消息发送状态三件套：前端据此在气泡上渲染「发送中/已发送/失败+原因/结果未知」
        respVO.setTaskId(message.getTaskId());
        respVO.setSendStatus(message.getSendStatus());
        respVO.setFailReason(message.getFailReason());
        respVO.setCreateTime(message.getCreateTime());
        return respVO;
    }

    @Override
    public List<XianyuConversationRespVO> getConversationList(XianyuConversationListReqVO reqVO) {
        // 聚合两步 SQL + 内存组装（租户拦截器自动过滤 tenant_id；聚合键含 item 维度，13 号 D-13-3）
        List<XianyuChatMessageMapper.ConversationLatestRow> latestRows = messageMapper.selectConversationLatestIds();
        List<XianyuChatMessageMapper.ConversationUnreadRow> unreadRows = messageMapper.selectConversationUnreadCounts();
        if (latestRows.isEmpty()) {
            return Collections.emptyList();
        }
        // 未读数索引：aggKey + '\u0000' + convId + '\u0000' + itemId → unread
        Map<String, Long> unreadMap = unreadRows.stream().collect(Collectors.toMap(
                row -> conversationKey(row.getAggKey(), row.getConvId(), row.getItemId()),
                XianyuChatMessageMapper.ConversationUnreadRow::getUnread, (a, b) -> a));
        // 批量查最新消息行
        List<Long> latestIds = latestRows.stream()
                .map(XianyuChatMessageMapper.ConversationLatestRow::getLatestId)
                .toList();
        Map<Long, XianyuChatMessageDO> messageMap = messageMapper.selectBatchIds(latestIds).stream()
                .collect(Collectors.toMap(XianyuChatMessageDO::getId, Function.identity()));
        // 组装
        List<XianyuConversationRespVO> list = new ArrayList<>();
        for (XianyuChatMessageMapper.ConversationLatestRow row : latestRows) {
            XianyuChatMessageDO message = messageMap.get(row.getLatestId());
            if (message == null) {
                continue;
            }
            // 会话绑定商品 = 该分组消息行的 item_id（0 = 未关联商品会话，13 号 §七.3）
            Long itemId = row.getItemId() != null && row.getItemId() > 0 ? row.getItemId() : null;
            XianyuConversationRespVO respVO = new XianyuConversationRespVO();
            respVO.setAccountId(message.getAccountId());
            respVO.setAccountNick(message.getAccountNick());
            respVO.setBuyerNick(message.getBuyerNick());
            // 会话唯一标识：取该组最新消息行的 chat_id（同 convId 组内一致；noncid 组为 null，前端按昵称兜底）
            respVO.setChatId(trimToNull(message.getChatId()));
            respVO.setItemId(itemId);
            respVO.setLastMessageContent(message.getContent());
            respVO.setLastMessageContentType(message.getContentType());
            respVO.setLastMessageDirection(message.getDirection());
            respVO.setLastMessageTime(message.getReceiveTime());
            respVO.setUnreadCount(unreadMap.getOrDefault(
                    conversationKey(row.getAggKey(), row.getConvId(), row.getItemId()), 0L));
            list.add(respVO);
        }
        // 商品信息回填（评审 P2-A：批量一次查询替代循环内 selectOne，防 1+N；会话列表上百行 × 前端轮询）：
        // 收集 distinct (accountId, itemId) → 单次 IN 查询 → 按 account|item 建映射；
        // 查不到（存量商品未回填 / account 未对齐）→ null，前端兜底「商品#itemId」（13 号 §七.3）
        // 商品表未命中的会话（买家发送非目录商品卡）→ 用最近一张商品卡 ext 兜底主图/展示价/链接，
        // 让「回复时可见商品信息」对非目录商品也成立（13 号 §七.3）
        fillConversationProductsFromCardExt(list);
        // 会话级商品快照兜底（方案 A）：item_id 为 NULL 的「未关联商品」会话也能显示行缩略图/头部价格；
        // 必须排在 card-ext 之后——card-ext 的 missed 过滤条件含 productImageUrl/productPrice，
        // 若提前填 snapshot 会把它打掉导致 productUrl 丢失
        fillConversationProductSnapshot(list);
        // 可选筛选：accountId / accountNick 精确匹配（§5.2-⑤ 未标「模糊」，按精确处理）
        if (reqVO.getAccountId() != null) {
            list.removeIf(respVO -> !reqVO.getAccountId().equals(respVO.getAccountId()));
        }
        if (StringUtils.hasText(reqVO.getAccountNick())) {
            list.removeIf(respVO -> !reqVO.getAccountNick().equals(respVO.getAccountNick()));
        }
        // 最新消息时间倒序
        list.sort((a, b) -> {
            if (a.getLastMessageTime() == null) {
                return 1;
            }
            if (b.getLastMessageTime() == null) {
                return -1;
            }
            return b.getLastMessageTime().compareTo(a.getLastMessageTime());
        });
        return list;
    }

    /**
     * 会话列表商品卡信息批量回填（评审 P2-A：消除循环内 selectOne 的 1+N）。
     * 收集 distinct (accountId, itemId) → 单次 IN 查询 → account|item 映射；未命中项保持三字段为 null（前端兜底）。
     */
    /**
     * 商品表未命中会话的 ext_json 兜底（13 号 §七.3）：买家常发送非目录商品卡，商品表无行 → 头部/列表只剩「商品#itemId」。
     * 按会话分组取「最近一张商品卡」的 ext 快照，回填主图/展示价/链接（目录命中时不覆盖）。单次分组查询，非 1+N。
     */
    private void fillConversationProductsFromCardExt(List<XianyuConversationRespVO> list) {
        List<XianyuConversationRespVO> missed = list.stream()
                .filter(v -> v.getItemId() != null && v.getProductImageUrl() == null && v.getProductPrice() == null)
                .toList();
        if (missed.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> extMap = messageMapper.selectLatestCardExtPerConversation().stream()
                .filter(r -> StringUtils.hasText(r.getExtJson()))
                .collect(Collectors.toMap(
                        r -> conversationKey(r.getAggKey(), r.getConvId(), r.getItemId()),
                        r -> parseCardExt(r.getExtJson()),
                        (a, b) -> a));
        for (XianyuConversationRespVO v : missed) {
            String aggKey = v.getAccountId() != null
                    ? String.valueOf(v.getAccountId()) : "unbound:" + v.getAccountNick();
            Map<String, Object> ext = extMap.get(conversationKey(aggKey,
                    convIdOf(v.getChatId(), v.getBuyerNick()), v.getItemId()));
            if (ext == null) {
                continue;
            }
            // 标题多为空（webim 不可提取），仅在目录未命中且 ext 有标题时补充，否则前端保留「商品#itemId」兜底
            String title = strOf(ext.get("title"));
            if (!StringUtils.hasText(v.getProductTitle()) && StringUtils.hasText(title)) {
                v.setProductTitle(title);
            }
            String imageUrl = strOf(ext.get("imageUrl"));
            if (StringUtils.hasText(imageUrl)) {
                v.setProductImageUrl(imageUrl);
            }
            String price = strOf(ext.get("price"));
            if (StringUtils.hasText(price)) {
                v.setProductPriceText(price);
            }
            String url = strOf(ext.get("url"));
            if (StringUtils.hasText(url)) {
                v.setProductUrl(url);
            }
        }
    }

    /**
     * 会话级商品快照兜底（方案 A，2026-09-24）：Agent 每条上报消息都带 product_image_url
     * （会话行右侧商品缩略图）/ product_price_text（会话内最近一张商品卡价格文本）。
     * 按会话取「最近一条非空快照」回填，<b>仅填空不覆盖</b>——商品目录命中 / 商品卡 ext 已填的不动。
     * <p>
     * 价值：item_id 为 NULL 的「未关联商品」会话也能在左栏/头部看到商品，修复「管理员不知道用户说的是哪个商品」。
     * <p>
     * 价格文本仅对未绑定 itemId 的会话填充，避免覆盖商品表的分价（productPrice，展示为元）——
     * 已绑定会话的价格优先取目录/卡片 ext，口径与 13 号 §七.3 一致。
     */
    private void fillConversationProductSnapshot(List<XianyuConversationRespVO> list) {
        List<XianyuConversationRespVO> missing = list.stream()
                .filter(v -> v.getProductImageUrl() == null || v.getProductPriceText() == null)
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<String, XianyuChatMessageMapper.ConversationProductSnapshotRow> snapshotMap =
                messageMapper.selectLatestProductSnapshotPerConversation().stream()
                        .collect(Collectors.toMap(
                                r -> conversationKey(r.getAggKey(), r.getConvId(), r.getItemId()),
                                Function.identity(), (a, b) -> a));
        for (XianyuConversationRespVO v : missing) {
            String aggKey = v.getAccountId() != null
                    ? String.valueOf(v.getAccountId()) : "unbound:" + v.getAccountNick();
            Long keyItemId = v.getItemId() != null ? v.getItemId() : 0L;
            XianyuChatMessageMapper.ConversationProductSnapshotRow row =
                    snapshotMap.get(conversationKey(aggKey, convIdOf(v.getChatId(), v.getBuyerNick()), keyItemId));
            if (row == null) {
                continue;
            }
            if (v.getProductImageUrl() == null && StringUtils.hasText(row.getProductImageUrl())) {
                v.setProductImageUrl(row.getProductImageUrl());
            }
            if (v.getItemId() == null && v.getProductPriceText() == null
                    && StringUtils.hasText(row.getProductPriceText())) {
                v.setProductPriceText(row.getProductPriceText());
            }
            // 商品标题兜底（2026-09-24）：目录未命中时用会话行快照标题，仍无则前端「商品#itemId」兜底
            if (!StringUtils.hasText(v.getProductTitle()) && StringUtils.hasText(row.getProductTitle())) {
                v.setProductTitle(row.getProductTitle());
            }
        }
    }

    private Map<String, Object> parseCardExt(String extJson) {
        try {
            Map<String, Object> map = JsonUtils.parseObject(extJson, Map.class);
            return map != null ? map : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("[xianyu-message] 商品卡 ext 兜底解析失败: {}", extJson);
            return Collections.emptyMap();
        }
    }

    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 商品主图 JSON 数组（["url",...]）取第一张；解析失败返回 null（前端按无图处理）。
     */
    private String parseFirstImage(String imagesJson) {
        if (!StringUtils.hasText(imagesJson)) {
            return null;
        }
        try {
            List<String> images = JsonUtils.parseArray(imagesJson, String.class);
            return images != null && !images.isEmpty() ? images.get(0) : null;
        } catch (Exception e) {
            log.warn("[xianyu-message] 商品主图解析失败: {}", imagesJson);
            return null;
        }
    }

    @Override
    public int markConversationRead(XianyuConversationReadReqVO reqVO) {
        // 定位口径与 getConversationList 一致：已对齐账号优先按 accountId，未对齐按昵称快照兜底；
        // convId + item 维度 NULL 匹配 NULL（打开同一买家的 A 商品会话不得清掉 B 商品会话红点）
        String chatId = trimToNull(reqVO.getChatId());
        if (reqVO.getAccountId() != null) {
            return messageMapper.readConversationByAccount(reqVO.getAccountId(), reqVO.getBuyerNick(),
                    chatId, reqVO.getItemId());
        }
        if (!StringUtils.hasText(reqVO.getAccountNick())) {
            // accountId 与 accountNick 均缺失，无法定位会话（buyerNick 已在 VO 层 @NotEmpty 校验）
            return 0;
        }
        return messageMapper.readConversationByNick(reqVO.getAccountNick(), reqVO.getBuyerNick(),
                chatId, reqVO.getItemId());
    }

    /**
     * 会话唯一主键（与 {@code XianyuChatMessageMapper} 聚合口径一致）：
     * chat_id 非空用其值，否则 'noncid:' + buyer_nick（无会话 id 的存量行按买家聚合）。
     */
    private static String convIdOf(String chatId, String buyerNick) {
        return StringUtils.hasText(chatId) ? chatId : "noncid:" + buyerNick;
    }

    private String conversationKey(String aggKey, String convId, Long itemId) {
        return aggKey + '\u0000' + convId + '\u0000' + itemId;
    }

    @Override
    public Long getUnreadCount() {
        // 全局未读总数（14 号 §5.1）：口径与 selectConversationUnreadCounts 一致，
        // 保证标题/菜单徽标 = 工作台左栏各会话未读之和；COUNT 为 null 防御归零
        Long count = messageMapper.selectUnreadCount();
        return count != null ? count : 0L;
    }

}
