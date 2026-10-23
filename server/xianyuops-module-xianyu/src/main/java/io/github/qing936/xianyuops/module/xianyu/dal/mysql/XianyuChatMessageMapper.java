package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuMessagePageReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuChatMessageDO;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface XianyuChatMessageMapper extends BaseMapperX<XianyuChatMessageDO> {

    /**
     * 窗口判重（无永久唯一键，先查再插）：
     * agent + 账号昵称快照 + 买家昵称 + 类型 + 内容（媒体用 dedupKey）+ item 维度 + chat 维度 + 5 分钟窗口内已存在 → 判重命中。
     * <p>
     * item 维度（13 号 D-13-5）：同名买家两个商品会话发相同内容（如「在吗」）不得互相判重，
     * NULL 匹配 NULL（历史/未关联商品消息 item_id 为 NULL 时互判重仍成立）。
     * chat 维度（2026-09-24 会话唯一性）：默认同一会话必不同买家，但同一买家的两个
     * 会话（不同商品）内容碰巧相同时不得互相判重，故并入会话唯一 id。
     * 租户条件由租户拦截器自动追加。
     */
    @Select("""
            <script>
            SELECT id FROM app_xianyu_chat_message
            WHERE agent_id = #{agentId}
              AND account_nick = #{accountNick}
              AND buyer_nick = #{buyerNick}
              AND content_type = #{contentType}
            <choose>
                <when test="mediaKey != null and mediaKey != ''">
                  AND JSON_UNQUOTE(JSON_EXTRACT(
                      CASE WHEN JSON_VALID(ext_json) THEN ext_json ELSE NULL END, '$.dedupKey')) = #{mediaKey}
                </when>
                <otherwise>
                  AND content = #{content}
                </otherwise>
            </choose>
              AND ((item_id = #{itemId}) OR (item_id IS NULL AND #{itemId} IS NULL))
              AND ((chat_id = #{chatId}) OR (chat_id IS NULL AND #{chatId} IS NULL))
              AND receive_time > #{windowStart}
              AND deleted = 0
            LIMIT 1
            </script>
            """)
    Long selectDedupHit(@Param("agentId") Long agentId,
                        @Param("accountNick") String accountNick,
                        @Param("buyerNick") String buyerNick,
                        @Param("content") String content,
                        @Param("contentType") String contentType,
                        @Param("mediaKey") String mediaKey,
                        @Param("itemId") Long itemId,
                        @Param("chatId") String chatId,
                        @Param("windowStart") LocalDateTime windowStart);

    /**
     * 全局未读消息总数（全局提醒轮询用，轻量 COUNT，14 号 §5.1）：
     * 口径与 {@link #selectConversationUnreadCounts()} 完全一致（deleted=0 AND msg_status='unread'），
     * 保证全局总数 = 工作台左栏各会话未读徽标之和，标题/菜单徽标与页面内红点永远对得上。
     * 租户条件由租户拦截器自动追加（建议复合索引 idx_tenant_msg_status，见 14 号 §5.1 DDL）。
     */
    @Select("""
            SELECT COUNT(*) FROM app_xianyu_chat_message
            WHERE deleted = 0 AND msg_status = 'unread'
            """)
    Long selectUnreadCount();

    /**
     * 会话聚合第一步：每个会话（agg_key + convId + item_id）的最新一条消息 id。
     * agg_key = COALESCE(account_id, 'unbound:' + account_nick)——未对齐账号用昵称快照兜底，防同名买家跨账号塌缩。
     * convId = COALESCE(NULLIF(chat_id, ''), 'noncid:' + buyer_nick)——**会话唯一主键**（2026-09-24 会话唯一性修复）：
     * 有 chat_id（= 会话行 sessionInfo.sessionId）时按会话精确拆分，同一买家对多个商品的会话不再被合并；
     * 无 chat_id 的存量行退化为 'noncid:' + 昵称 仍按买家聚合，不会炸成每行一会话。
     * item 维度（13 号 D-13-3）：同买家多商品会话分组，COALESCE(item_id, 0) 归一 NULL 组。
     */
    @Select("""
            SELECT COALESCE(CAST(account_id AS CHAR), CONCAT('unbound:', account_nick)) AS aggKey,
                   buyer_nick AS buyerNick,
                   COALESCE(item_id, 0) AS itemId,
                   COALESCE(NULLIF(chat_id, ''), CONCAT('noncid:', buyer_nick)) AS convId,
                   MAX(id) AS latestId
            FROM app_xianyu_chat_message
            WHERE deleted = 0
            GROUP BY aggKey, buyerNick, itemId, convId
            """)
    List<ConversationLatestRow> selectConversationLatestIds();

    /**
     * 回复发送弱校验的「会话已知商品集合」（13 号 §五 增强 B）：某账号×买家会话在库中出现过的
     * 全部非空 item_id 去重集合。Agent 打开会话时目标商品卡若不在可视窗口（同买家多商品会话常见），
     * 用此集合兜底放行，避免对旧商品的回复被误拒；仅当目标 item 从未在该会话出现才拒发。
     * 租户条件由租户拦截器自动追加。
     */
    @Select("""
            SELECT DISTINCT item_id FROM app_xianyu_chat_message
            WHERE account_id = #{accountId}
              AND buyer_nick = #{buyerNick}
              AND item_id IS NOT NULL
              AND item_id > 0
              AND deleted = 0
            """)
    List<Long> selectKnownItemIds(@Param("accountId") Long accountId,
                                  @Param("buyerNick") String buyerNick);

    /**
     * 会话聚合第二步：各会话未读数（convId + item 维度与 {@link #selectConversationLatestIds()} 同口径）。
     */
    @Select("""
            SELECT COALESCE(CAST(account_id AS CHAR), CONCAT('unbound:', account_nick)) AS aggKey,
                   buyer_nick AS buyerNick,
                   COALESCE(item_id, 0) AS itemId,
                   COALESCE(NULLIF(chat_id, ''), CONCAT('noncid:', buyer_nick)) AS convId,
                   COUNT(*) AS unread
            FROM app_xianyu_chat_message
            WHERE deleted = 0 AND msg_status = 'unread'
            GROUP BY aggKey, buyerNick, itemId, convId
            """)
    List<ConversationUnreadRow> selectConversationUnreadCounts();

    /**
     * 会话标记已读（已对齐账号）：按 account_id + convId + item_id 定位（NULL 匹配 NULL）。
     * 只把 direction=in 且 msg_status=unread 的行置为 read；replied 不降级
     * （已回复的历史消息无需回退，否则会话未读口径会被历史回复行污染）。
     * convId 维度（2026-09-24）：打开同一买家的 A 商品会话不得清掉 B 商品会话红点；
     * 传入 chatId 为空时退回非 chat_id 行（NULL 匹配 NULL），兼容无会话 id 的存量行。
     * 租户条件由租户拦截器自动追加。
     */
    @Update("""
            UPDATE app_xianyu_chat_message
            SET msg_status = 'read'
            WHERE account_id = #{accountId}
              AND buyer_nick = #{buyerNick}
              AND ((chat_id = #{chatId}) OR (chat_id IS NULL AND #{chatId} IS NULL))
              AND ((item_id = #{itemId}) OR (item_id IS NULL AND #{itemId} IS NULL))
              AND direction = 'in'
              AND msg_status = 'unread'
              AND deleted = 0
            """)
    int readConversationByAccount(@Param("accountId") Long accountId, @Param("buyerNick") String buyerNick,
                                  @Param("chatId") String chatId, @Param("itemId") Long itemId);

    /**
     * 会话标记已读（未对齐账号兜底）：account_id IS NULL 时按账号昵称快照 + buyer_nick + convId + item_id 定位。
     * 聚合键口径见 {@link #selectConversationLatestIds()} 的 unbound:account_nick。
     */
    @Update("""
            UPDATE app_xianyu_chat_message
            SET msg_status = 'read'
            WHERE account_id IS NULL
              AND account_nick = #{accountNick}
              AND buyer_nick = #{buyerNick}
              AND ((chat_id = #{chatId}) OR (chat_id IS NULL AND #{chatId} IS NULL))
              AND ((item_id = #{itemId}) OR (item_id IS NULL AND #{itemId} IS NULL))
              AND direction = 'in'
              AND msg_status = 'unread'
              AND deleted = 0
            """)
    int readConversationByNick(@Param("accountNick") String accountNick, @Param("buyerNick") String buyerNick,
                               @Param("chatId") String chatId, @Param("itemId") Long itemId);

    default PageResult<XianyuChatMessageDO> selectPage(XianyuMessagePageReqVO reqVO) {
        LambdaQueryWrapperX<XianyuChatMessageDO> wrapper = new LambdaQueryWrapperX<XianyuChatMessageDO>()
                .eqIfPresent(XianyuChatMessageDO::getAgentId, reqVO.getAgentId())
                .eqIfPresent(XianyuChatMessageDO::getAccountId, reqVO.getAccountId())
                .likeIfPresent(XianyuChatMessageDO::getAccountNick, reqVO.getAccountNick())
                .likeIfPresent(XianyuChatMessageDO::getBuyerNick, reqVO.getBuyerNick())
                .eqIfPresent(XianyuChatMessageDO::getMsgStatus, reqVO.getMsgStatus())
                .eqIfPresent(XianyuChatMessageDO::getDirection, reqVO.getDirection());
        // item 维度过滤（13 号 §4.4 契约）：itemId 非空 → 精确匹配；itemId 空且 itemUnbound=true → 匹配 item_id IS NULL
        // eqIfPresent 表达不了「NULL 匹配」，用显式参数（13 号文档写死契约）
        if (StringUtils.hasText(reqVO.getItemId())) {
            wrapper.eq(XianyuChatMessageDO::getItemId, Long.valueOf(reqVO.getItemId()));
        } else if (Boolean.TRUE.equals(reqVO.getItemUnbound())) {
            wrapper.isNull(XianyuChatMessageDO::getItemId);
        }
        wrapper.orderByDesc(XianyuChatMessageDO::getReceiveTime);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 各会话「最近一张商品卡」的 ext_json（商品表未命中时头部/列表兜底数据源，13 号 §七.3）。
     * 口径与 {@link #selectConversationLatestIds()} 完全一致（aggKey + convId + itemId），
     * 仅取 content_type='card' 且 item_id 非空的分组，按 MAX(id) 定位最近卡片。租户条件由拦截器追加。
     */
    @Select("""
            SELECT m.aggKey AS aggKey, m.buyerNick AS buyerNick, m.itemId AS itemId, m.convId AS convId,
                   c.ext_json AS extJson
            FROM (
                SELECT COALESCE(CAST(account_id AS CHAR), CONCAT('unbound:', account_nick)) AS aggKey,
                       buyer_nick AS buyerNick,
                       COALESCE(item_id, 0) AS itemId,
                       COALESCE(NULLIF(chat_id, ''), CONCAT('noncid:', buyer_nick)) AS convId,
                       MAX(id) AS cardId
                FROM app_xianyu_chat_message
                WHERE deleted = 0 AND content_type = 'card' AND item_id IS NOT NULL
                GROUP BY aggKey, buyerNick, itemId, convId
            ) m
            JOIN app_xianyu_chat_message c ON c.id = m.cardId
            """)
    List<ConversationCardExtRow> selectLatestCardExtPerConversation();

    /**
     * 各会话「最近一条非空商品快照」的图/价/标题（2026-09-24；管理端左栏每行缩略图 + 头部展示）。
     * 口径与 {@link #selectConversationLatestIds()} 一致（aggKey + convId + itemId），
     * 仅取 product_image_url / product_price_text / product_title 至少一个非空的分组，按 MAX(id) 定位最近快照。
     * 与 {@link #selectLatestCardExtPerConversation()} 的区别：不要求 item_id 非空，故 item_id 为 NULL
     * 的「未关联商品」会话也能命中（这正是列表显示「未关联商品」的修复点）。租户条件由拦截器追加。
     */
    @Select("""
            SELECT m.aggKey AS aggKey, m.buyerNick AS buyerNick, m.itemId AS itemId, m.convId AS convId,
                   c.product_image_url AS productImageUrl, c.product_price_text AS productPriceText,
                   c.product_title AS productTitle
            FROM (
                SELECT COALESCE(CAST(account_id AS CHAR), CONCAT('unbound:', account_nick)) AS aggKey,
                       buyer_nick AS buyerNick,
                       COALESCE(item_id, 0) AS itemId,
                       COALESCE(NULLIF(chat_id, ''), CONCAT('noncid:', buyer_nick)) AS convId,
                       MAX(id) AS snapshotId
                FROM app_xianyu_chat_message
                WHERE deleted = 0
                  AND (product_image_url IS NOT NULL OR product_price_text IS NOT NULL
                       OR product_title IS NOT NULL)
                GROUP BY aggKey, buyerNick, itemId, convId
            ) m
            JOIN app_xianyu_chat_message c ON c.id = m.snapshotId
            """)
    List<ConversationProductSnapshotRow> selectLatestProductSnapshotPerConversation();

    /** 会话最新一条消息行（聚合中间结果） */
    @Data
    static class ConversationLatestRow {

        private String aggKey;

        private String buyerNick;

        /** COALESCE(item_id, 0)：0 = 未关联商品会话 */
        private Long itemId;

        /** 会话唯一主键：chat_id 优先，空则 'noncid:' + buyer_nick（与未读/SQL 聚合口径一致） */
        private String convId;

        private Long latestId;
    }

    /** 会话未读数行（聚合中间结果） */
    @Data
    static class ConversationUnreadRow {

        private String aggKey;

        private String buyerNick;

        /** COALESCE(item_id, 0)：0 = 未关联商品会话 */
        private Long itemId;

        /** 会话唯一主键（口径同 ConversationLatestRow.convId） */
        private String convId;

        private Long unread;
    }

    /** 会话最近商品卡 ext_json 行（聚合中间结果，商品表未命中时头部/列表兜底用） */
    @Data
    static class ConversationCardExtRow {

        private String aggKey;

        private String buyerNick;

        /** COALESCE(item_id, 0)：卡片分组恒为非 0 */
        private Long itemId;

        /** 会话唯一主键（口径同 ConversationLatestRow.convId） */
        private String convId;

        private String extJson;
    }

    /** 会话最近非空商品快照行（聚合中间结果，item_id 0 = 未关联商品会话） */
    @Data
    static class ConversationProductSnapshotRow {

        private String aggKey;

        private String buyerNick;

        /** COALESCE(item_id, 0)：0 = 未关联商品会话 */
        private Long itemId;

        /** 会话唯一主键（口径同 ConversationLatestRow.convId） */
        private String convId;

        private String productImageUrl;

        private String productPriceText;

        /** 会话商品快照-标题（目录未命中时的展示兜底） */
        private String productTitle;
    }

}
