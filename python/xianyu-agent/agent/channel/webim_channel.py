"""WebimChannel：闲鱼 Web IM 浏览器 DOM 通道（10 号文档 M2）。

实现 Channel 三方法 + 生命周期钩子，与 ui_ocr 保持状态机零改动兼容：

- read_accounts：真实登录态探测（P1-2）——accounts.json 只作「账号清单 + Cookie 源」，
  每账号常驻 AccountPage，ready() 聚合登录态；needLogin/blocked 账号从快照剔除，
  避免恒成功快照让 _blocked_check 每 10s 触发 BLOCKED↔NORMAL 抖动并反复清游标。
- read_unread：滚动循环 Python 化（P2-3，STEP_COLLECT_JS 每步一次 evaluate，步间
  on_progress/paused_check）；门控/过滤移植 ui_ocr §5.6 + 07 R1 语义（首轮基线 /
  T1.1 时间前进 / T1.2 兜底 / T1.4 熔断）；打开会话后 WsFrames 抓 buyer_id/chat_id
  （09 §2.3）。
- send_message：open → HEADER_JS 身份闸门（P1-4，不一致绝不发送）→ 写入验证 →
  发送 → DOM 气泡读回比对 → 三态 SendResult（headless 无截图，verify_image_path=None）
  → 导航复位。

降级语义（10 号文档 §三）：

| ChannelError type | 触发条件 | 效果 |
|---|---|---|
| window_missing | 浏览器进程死亡 / 调试口连不上 / 回列表失败 | 3 连进 IDLE；health_check 指数退避重启（日限 3 次，P1-5），超限转人工 |
| login_required | 页面出现登录特征（Cookie 失效） | BLOCKED 等人工重新导 Cookie；read_accounts 里仅从快照剔除（不动全局模式） |
| blocked（通道内部信号，不冒泡状态机） | 页面「非法访问」风控提示（P1-3 punish 类） | 账号级冷却 punish_cooldown_sec，到期自动恢复；不映射 login_required（自愈型瞬时风控，映射过去每次 punish 都要人工介入） |
| buyer_missing | 会话列表找不到目标买家 / header 身份校验未通过 | 记日志跳过（read）或回传 failed（send） |
| paused | paused_check() 命中（滚动循环步间查） | 转 PAUSED（R5 人工接管语义保留） |

浏览器长驻复用（09 §2.7：短时高频启停曾触发 3 连 x5sec punish），
多账号 = 每账号一个 Target.createBrowserContext 隔离页面。
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import math
import os
import re
import socket
import time
import uuid
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import unquote, urlsplit, urlunsplit

import requests

from agent.channel.base import Channel, ChannelError, Message, SendResult
from agent.channel.webim.browser import (
    APP_UA,
    BACK_JS,
    HEADER_JS,
    STEP_COLLECT_JS,
    AccountPage,
    Browser,
    WsFrames,
    attach_times,
    parse_msg_time,
)
from agent.channel.webim.cdp import http_json
from agent.channel.webim.store import load_accounts_file, resolve_accounts
from agent.ui.time_parser import parse as parse_time_text


def _sha1(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


_MEDIA_LABELS = {"image": "[图片]", "emoji": "[表情]", "audio": "[语音]", "video": "[视频]"}
# 媒体可信域名：阿里系 CDN + 闲鱼/本项目域名 + 钉钉 IM 媒体 CDN（webim 公开产物常量，
# 含 impaas 侧 dingtalk.com 与 down.im.dingtalk.cn 两套下载域）
_MEDIA_HOSTS = ("alicdn.com", "taobaocdn.com", "tbcdn.cn", "aliyuncs.com", "goofish.com",
                "dingtalk.com", "dingtalk.cn", "aliimg.com")


def _media_url(value) -> str | None:
    if not isinstance(value, str) or len(value) > 4096:
        return None
    if any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in unquote(value)):
        return None
    value = value.strip()
    if value.startswith("//"):
        value = "https:" + value
    try:
        url = urlsplit(value)
        host = (url.hostname or "").lower()
        if url.scheme not in ("http", "https") or url.username is not None or url.password is not None:
            return None
        if url.port not in (None, 443 if url.scheme == "https" else 80):
            return None
        if url.netloc.lower() != host + (":" + str(url.port) if url.port is not None else ""):
            return None
        if not any(host == domain or host.endswith("." + domain) for domain in _MEDIA_HOSTS):
            return None
        normalized = urlunsplit(("https", host, url.path or "/", url.query, url.fragment))
        return normalized if len(normalized) <= 4096 else None
    except ValueError:
        return None


def _media_host_hint(value) -> str:
    """被拒 URL 的裸主机名（不含路径/查询），用于降级文案定位可信域名缺口。"""
    if not isinstance(value, str):
        return ""
    match = re.match(r"^(?:[a-zA-Z][a-zA-Z0-9+.\-]*:)?//([^/?#]*)", value.strip())
    if not match:
        return ""
    host = match.group(1).rsplit("@", 1)[-1].split(":", 1)[0].lower()
    return host[:60]


def _media_line(event: dict, now: datetime) -> dict | None:
    content_type = event.get("content_type")
    if content_type not in _MEDIA_LABELS:
        return None
    items = []
    raw_items = event.get("items")
    for raw in (raw_items[:20] if isinstance(raw_items, list) else []):
        if not isinstance(raw, dict) or raw.get("type") not in _MEDIA_LABELS:
            continue
        item = {"type": raw["type"]}
        for field, limit in (("alt", 500), ("unavailableReason", 200)):
            value = raw.get(field)
            if isinstance(value, str) and value.strip():
                item[field] = value.strip()[:limit]
        for field in ("url", "posterUrl"):
            value = _media_url(raw.get(field))
            if value:
                item[field] = value
        duration = raw.get("duration")
        if isinstance(duration, (int, float)) and not isinstance(duration, bool) and math.isfinite(duration) and 0 <= duration <= 86400:
            item["duration"] = duration
        if not item.get("url") and not (item["type"] == "emoji" and item.get("alt")):
            raw_url = raw.get("url")
            if raw_url and "unavailableReason" not in item:
                hint = _media_host_hint(raw_url)
                item["unavailableReason"] = "媒体地址不在可信范围" + (f"（host={hint}）" if hint else "")
            else:
                item.setdefault("unavailableReason", "闲鱼网页未提供媒体地址")
        items.append(item)
    if not items:
        items = [{"type": content_type, "unavailableReason": "闲鱼网页未提供媒体地址"}]
    ext = {"type": "media", "items": items}
    source_id = event.get("source_message_id")
    if isinstance(source_id, (str, int)) and not isinstance(source_id, bool):
        source_id = str(source_id).strip()
        if source_id and len(source_id) <= 128:
            ext["sourceMessageId"] = source_id
    received = parse_msg_time(event.get("time_text") or "", now)
    timestamp = event.get("timestamp")
    try:
        value = float(timestamp)
        if value >= 1e12:
            value /= 1000
        if math.isfinite(value) and 946684800 <= value <= 4102444800:
            received = datetime.fromtimestamp(value)
    except (TypeError, ValueError, OverflowError, OSError):
        pass
    text = event.get("text")
    direction = event.get("dir")
    return {"kind": "media", "direction": "in" if direction in ("in", "?") else "out", "content_type": content_type,
            "text": text.strip()[:5000] if isinstance(text, str) and text.strip() else _MEDIA_LABELS[content_type],
            "receiveTime": received, "item_id": "", "ext": ext}


# 与 ui_ocr._PUNCT_RE 一致：比对时剔除的空白/标点（气泡断行/标点抖动，逐字比对不可靠）
_PUNCT_RE = re.compile(r"[\s，。！？、；：,.!?~～;:|/（）()\[\]【】<>《》·．]")
# 会话行「相对时间」形态（parts[1] 命中即视为时间而非摘要）
_TIME_LIKE_RE = re.compile(r"前$|^(昨天|前天)|\d{1,2}:\d{2}|^\d{2}-\d{2}")


def _normalize(text: str) -> str:
    """去空白与常见标点，用于气泡/摘要的宽松比对与 hash。"""
    return _PUNCT_RE.sub("", text or "")


def _hash_norm(text: str) -> str:
    """hash 专用归一化：纯标点内容归一化后为空时回退原文（strip）。

    否则「，，，」与「？？？」都归一为空串 → hash 碰撞，后者被误判重放丢弃、
    会话预览同理互相抵充（2026-09-22「？？？」漏报根因）。普通文本行为不变。
    """
    return _normalize(text) or (text or "").strip()


# 订单/评价类系统状态标签：会话行昵称节点首子元素为空时，STEP_COLLECT_JS 会回退取整块
# innerText，导致这些标签被误当成买家昵称（vm101 dump 实测 18 行）。内置拦截，不依赖各 VM
# 配置的 conversation_ignore_keywords，对所有部署一致生效。
_PSEUDO_NICKS = frozenset({
    "交易关闭", "交易成功", "有新交易评价", "待评价", "已评价", "交易待评价",
    "退款成功", "退款关闭", "待付款", "已付款", "待发货", "已发货", "退款中",
})


def _row_summary(row: dict) -> str:
    """会话行摘要 = parts[1]（预览叶子）；parts[1] 是时间文本时退空串。

    摘要进 summaryHash 做变化检测：若把时间当摘要，时间文本滚动会导致 hash
    每轮变化、会话被每轮重开（与 ui_ocr 摘要抖动同根因）。
    """
    parts = row.get("parts") or []
    if len(parts) > 1:
        p = (parts[1] or "").strip()
        if p and not _TIME_LIKE_RE.search(p):
            return p
    return ""


def _conv_key(row: dict) -> str:
    """会话行 → 会话唯一 key（2026-09-24 会话唯一性修复）。

    chat_id（= 会话行 sessionInfo.sessionId，browser.STEP_COLLECT_JS 直采）优先；
    无 chat_id 的存量/异常行退 'noncid:<昵称>'——与 Java convId 口径一致
    （COALESCE(NULLIF(chat_id,''), CONCAT('noncid:', buyer_nick))）。
    """
    cid = (row.get("chat_id") or "").strip()
    return cid if cid else ("noncid:" + (row.get("buyerNick") or "").strip())


def _select_readable(rows: list[dict], cursor, gate: dict, now: datetime, log=None) -> tuple[list[dict], bool]:
    """SCAN 门控（移植 ui_ocr §5.6 + 07 R1）：返回 (readable, first_round)。

    纯函数语义（无 IO，游标/行显式传入），供 m2_smoke 直接单测。
    - 首轮：建基线（summary hash / 时间估计），仅带未读角标的会话进入读取；
    - 非首轮：摘要变(changed) / 未读数增(more) / 未读且未排空 / 时间前进(T1.1)
      任一成立才读；每 force_every 轮兜底强制打开 1 个活跃会话（T1.2）。
    """
    enable_time_change = bool(gate["enable_time_change"])
    enable_force_open = bool(gate["enable_force_open"])
    time_tol = gate["time_tol"]
    active_window = gate["active_window"]
    force_every = gate["force_every"]
    max_list_rows = gate["max_list_rows"]
    first_round = not cursor.initialized
    readable: list[dict] = []
    if first_round:
        for row in rows:
            c = cursor.conv(_conv_key(row), row["buyerNick"])
            c.last_summary_hash = row["summaryHash"]
            c.summary_hashes.add(row["summaryHash"])
            # T1.1：首轮同步登记时间估计，防多线程抖动致次轮「时间倒退」误触发
            if row.get("time_est") is not None:
                c.last_time_est = row["time_est"]
            else:
                c.last_time_text_norm = _normalize(row.get("time_text") or "") or None
        cursor.initialized = True
        cursor.started_at = now  # 本进程消息时间下界（重启后防历史重放）
        readable = [r for r in rows if r.get("unread")]
        if log:
            log.info("首轮扫描：%d 个会话已建基线，其中 %d 个有未读（读取上报）",
                     len(rows), len(readable))
    else:
        for row in rows:
            c = cursor.conv(_conv_key(row), row["buyerNick"])
            # 变化检测用「该昵称已见摘要 hash 集合」而非单值：同一昵称可有多条会话线程
            # （买家分商品会话），单值会在两线程间每轮互相覆盖、两行都被误判「摘要已变」
            changed = row["summaryHash"] not in c.summary_hashes
            more = (row.get("unread_count") or 0) > (c.last_unread_count or 0)
            row["_summary_changed"] = changed  # 本轮读取是否由「摘要变化」触发（供读取过滤判断）
            time_new = False
            if enable_time_change:
                t_est = row.get("time_est")
                if t_est is not None:
                    time_new = c.last_time_est is None or t_est > c.last_time_est + time_tol
                else:
                    tn = _normalize(row.get("time_text") or "")
                    time_new = bool(tn) and tn != (c.last_time_text_norm or "")
            row["_time_changed"] = time_new
            # 门控：摘要未变且未读数未增且（无角标或上次已排空）且时间未前进 → 本轮不打开
            if changed or more or (row.get("unread") and not c.drained) or time_new:
                readable.append(row)
    # T1.2：活跃会话周期兜底——门控全假时会话可能被永久跳过；每 force_every 轮强制
    # 打开 1 个活跃会话对账，走现有读取管线（content_hash 去重 + 安全边界全保留）。
    if not first_round:
        cursor.scan_rounds += 1
        if enable_force_open and len(rows) <= max_list_rows and cursor.scan_rounds % force_every == 0:
            # 2026-09-24：排重按会话唯一 key（同一买家多商品会话各自独立，不再互相排除）
            readable_keys = {_conv_key(r) for r in readable}
            # 候选不再排除黑名单会话：否则「被 T1.4 熔断」+「T1.2 又不重开」叠加成死锁，
            # 是否真正跳过由 read_unread 循环判（forced_open 可穿透黑名单）
            cands = [r for r in rows
                     if r.get("time_est") is not None
                     and now - r["time_est"] <= active_window
                     and _conv_key(r) not in readable_keys]
            if cands:
                forced = min(cands, key=lambda r: cursor.conv(_conv_key(r), r["buyerNick"]).last_forced_open
                             or datetime.min)
                forced["_forced"] = True
                readable.append(forced)
                if log:
                    log.info("兜底读取触发：强制打开活跃会话 %s（角标/摘要均无变化）",
                             forced["buyerNick"])
    return readable, first_round


def _filter_fresh(lines: list[dict], c, user_nick: str, buyer: str, now: datetime,
                  first_round: bool, has_unread: bool, summary_changed: bool,
                  is_new_conv: bool, started_at: datetime | None, conv_id: str = "") -> list[dict]:
    """聊天区行 → 新消息（移植 ui_ocr 读取过滤规则，纯函数语义供 m2_smoke 单测）。

    返回 [{content, receive_time(datetime), h(内容 hash), kind, item_id, ext}]：
    - 首轮未读会话：不做时间过滤，只取最后一条买家消息（防历史全量刷屏）；
    - 本进程首次读该会话（first_read）：一律登记 hash（防后续重放），只上报
      「能定时且晚于本进程启动时刻」的消息；
    - 已基线会话：时间不晚于游标且 hash 已见 → 重读丢弃；无法定时 → hash 已见丢弃，
      无未读角标且非「摘要变化触发的最后一条买家消息」丢弃；
    - 运行中新增会话：只报近 10 分钟消息（例外：摘要变化触发的最后一条买家消息放行）。
    - 13 号决策 5：hash 键加 item 维度——同名买家两个商品会话发相同内容（如「在吗」）
      不得互相判重；卡片 content 为空（标题不可提取），靠 item_id 维度区分不同卡。
    - 2026-09-24：hash 键再加 conv_id（会话唯一 id）维度——同一买家跨会话同文同 item
      场景不互相判重（游标已按 conv_id 隔离，hash 维度保证 clientMsgId 全局唯一）。
    """
    fresh: list[dict] = []
    first_read = not c.baselined
    in_idx = [i for i, ln in enumerate(lines) if ln["direction"] == "in"]
    last_in_idx = in_idx[-1] if in_idx else -1
    for idx, line in enumerate(lines):
        if line["direction"] != "in":
            continue
        content = line["text"]
        # 内容 hash 用归一化文本（去空白/标点）：DOM 对同一气泡的分片/标点抖动
        # 会导致原始内容 hash 每轮变化、进程内判重失效；纯标点回退原文防撞（_hash_norm）
        identity = _hash_norm(content)
        if line.get("kind") == "media":
            ext = line["ext"]
            identity = line["content_type"] + "|" + (ext.get("sourceMessageId") or json.dumps(
                [{k: item.get(k) for k in ("type", "url", "alt", "unavailableReason")}
                 for item in ext["items"]], ensure_ascii=False, sort_keys=True))
        h = _sha1(user_nick + "|" + conv_id + "|" + buyer + "|" + (line.get("item_id") or "")
                  + "|" + identity)
        t = line.get("receiveTime")
        is_last_in = idx == last_in_idx
        if first_round:
            # 首轮未读会话：启动时刻已存在的未读消息，时间必早于 started_at，
            # 故不做时间过滤，由下方 fresh[-1:] 截取最后一条（防历史全量）。
            # 未上报的历史同样登记 hash：否则下一轮它们既「未见」又早于游标，会整屏重放。
            c.content_hashes.add(h)
        elif first_read:
            # 本进程首次读该会话：读到的内容一律登记 hash（防后续重放），只上报
            # 「能定时且晚于本进程启动时刻」的消息；无法定时视为历史丢弃
            c.content_hashes.add(h)
            if t is None or (started_at is not None and t <= started_at):
                continue
        else:
            # 时间不晚于游标且 hash 已见 → 丢弃（重读/旧消息）
            if (t is not None and h in c.content_hashes
                    and c.last_receive_time is not None and t <= c.last_receive_time):
                continue
            if t is None:
                # 无法定时消息：hash 已见 → 必为重读/旧消息，丢弃
                if h in c.content_hashes:
                    continue
                # 无法定时且无未读角标 → 摘要抖动/我方消息触发的重读，不可信，不上报。
                # 例外：本轮由「摘要变化」触发且该消息是聊天区最后一条买家消息 → 可信
                if not has_unread and not (summary_changed and is_last_in):
                    continue
        # 运行中新增会话：只报近 10 分钟消息（防历史全量，首条触发消息必在窗口内）；
        # 例外：无法定时但已判「摘要变化 + 最后一条买家消息」可信的，放行
        if is_new_conv and (t is None or t < now - timedelta(minutes=10)) \
                and not (t is None and summary_changed and is_last_in):
            continue
        fresh.append({"content": content, "receive_time": t or now, "h": h,
                      "kind": line.get("kind", "msg"),
                      "content_type": line.get("content_type", "text"),
                      "item_id": line.get("item_id") or None,
                      "ext": line.get("ext")})
    if first_round:
        fresh = fresh[-1:]  # 首轮未读会话只报最后一条买家消息（防历史全量刷屏）
    return fresh


class WebimChannel(Channel):
    """浏览器 DOM 通道（headless Edge 长驻，多账号隔离 context）。"""

    needs_desktop = False

    def __init__(self, cfg: dict, log, audit, shots_dir):
        self.cfg = cfg
        self.log = log
        self.audit = audit
        w = (cfg.get("channel") or {}).get("webim") or {}
        self.port = int(w.get("port", 9333))
        self.headless = bool(w.get("headless", True))
        self.profile_dir = w.get("profile_dir") or ""
        self.browser_path = w.get("browser_path") or ""
        self.accounts_file = w.get("accounts_file") or ""
        self.export_port = int(w.get("export_port", 9401))
        self.ready_timeout = float(w.get("ready_timeout_sec", 60))
        self.open_retries = int(w.get("open_retries", 3))
        self.collect_steps = int(w.get("collect_steps", 30))
        # P1-3：punish/非法访问账号级冷却时长（自愈型风控，不映射 BLOCKED）
        self.punish_cooldown_sec = int(w.get("punish_cooldown_sec", 600))
        # 2026-09-30：会话列表不可渲染自愈（L1 软重载）——骨架屏停滞时页面自身不会恢复
        # （vm101 实测 7.5h+），必须有界地软重载；冷却防风控（频繁 navigate 触发 punish）。
        self.list_recover_enable = bool(w.get("list_recover_enable", True))
        self.list_recover_cooldown_sec = int(w.get("list_recover_cooldown_sec", 300))
        self.list_recover_timeout_sec = float(w.get("list_recover_timeout_sec", 60))
        # 15 号文档：媒体外发（默认关闭，M4 验收后由 config.local.yaml 打开）
        o = cfg.get("outbound") or {}
        self.outbound_enabled = bool(o.get("enabled", False))
        self.image_max_bytes = int(o.get("image_max_bytes", 3145727))
        self.allowed_formats = tuple(o.get("allowed_formats") or ["jpeg", "png", "gif", "bmp"])
        self.download_timeout_sec = float(o.get("download_timeout_sec", 20))
        self.verify_timeout_sec = float(o.get("verify_timeout_sec", 25))
        self.account_min_interval_sec = float(o.get("account_min_interval_sec", 3))
        self.cleanup_keep_failed_hours = float(o.get("cleanup_keep_failed_hours", 24))
        temp_dir = str(o.get("temp_dir") or "logs/outbox")
        self.outbox_dir = (Path(temp_dir) if os.path.isabs(temp_dir)
                           else Path(shots_dir).parent.parent / temp_dir)
        r = cfg.get("read") or {}
        self.read_enable_blacklist = bool(r.get("enable_blacklist", True))
        self.open_fail_max = int(r.get("open_fail_max", 3))
        self.blacklist_ttl = timedelta(minutes=int(r.get("blacklist_ttl_min", 30)))
        # P0-1（12 号文档 §4.3）：假昵称防御校验复用 ui.ignore 关键词（与 ui_ocr 同源配置）
        self.conv_ignore = list((cfg.get("ui") or {}).get("conversation_ignore_keywords", []) or [])
        self._gate = {  # _select_readable 纯函数入参（07 R1 门控配置）
            "enable_time_change": bool(r.get("enable_time_change", True)),
            "enable_force_open": bool(r.get("enable_force_open", True)),
            "time_tol": timedelta(seconds=int(r.get("time_tol_sec", 300))),
            "active_window": timedelta(minutes=int(r.get("active_window_min", 120))),
            "force_every": max(1, int(r.get("force_every", 10))),
            "max_list_rows": int(r.get("max_list_rows", 30)),
        }
        # 运行时状态
        self.browser: Browser | None = None
        self.frames: WsFrames | None = None
        self.accounts: list[dict] = []
        self._pages: dict[str, AccountPage] = {}          # userNick → 常驻页面
        self._punish_until: dict[str, datetime] = {}      # userNick → 风控冷却到期
        # 2026-09-30 列表停滞自愈：上次软重载时刻（冷却）+ 是否需 IDLE 巡检真修复（L2/L3）
        self._list_recover_at: dict[str, float] = {}      # userNick → 上次 L1 软重载时间戳
        self._list_recover_pending = False                # True → health_check 必须重建页面/浏览器
        self._restart_day = None                          # 日限重启计数（P1-5）
        self._restart_count = 0
        self._next_restart_at = 0.0
        self._media_last_at: dict[str, float] = {}        # userNick → 上次媒体发送时刻
        self.outbox_dir.mkdir(parents=True, exist_ok=True)
        self._cleanup_outbox()
        self._stats = {"rows": 0, "opened": 0, "read": 0, "sent": 0,
                       "sent_image": 0, "sent_emoji": 0}

    # ------------------------------------------------------------------ 生命周期
    def warmup(self) -> None:
        """进程启动预热：解析账号清单 → 启动浏览器（长驻）→ 逐账号预热页面。

        失败不抛（main.py 直接调用，异常会阻断启动）：浏览器启动失败由 read_accounts
        的 window_missing → 3 连 IDLE → health_check 日限 3 次重启自愈。
        """
        try:
            self.accounts = resolve_accounts(self.accounts_file, self.export_port, self.log)
        except Exception as e:
            self.log.error("账号清单解析失败（含调试口导出降级）: %s", e)
            self.accounts = []
            return
        if not self.accounts:
            self.log.warning("无可用账号（accounts.json 空且调试口导出无果），通道以 0 账号运行")
            return
        try:
            self._ensure_browser()
        except Exception as e:
            self.log.error("浏览器启动失败（IDLE 巡检中将按日限 3 次重试）: %s", e)
            return
        for acc in self.accounts:
            if self.on_progress:
                self.on_progress()
            nick = (acc.get("shop_name") or str(acc.get("user_id") or "")).strip()
            try:
                self._page_for({"userNick": nick})
            except Exception as e:
                self.log.warning("账号 %s 页面预热失败（read_accounts 会再试）: %s", nick, e)

    def health_check(self) -> None:
        """IDLE 巡检（P1-5）：浏览器探测；失败按指数退避重启（日限 3 次），超限抛 window_missing。

        2026-09-30：调试口存活 ≠ 页面可用——骨架屏停滞时端口始终是通的，原实现直接 return
        会被状态机当作「画面恢复」转 NORMAL，随即 SCAN 又失败 → IDLE↔SCAN 空转不收敛。
        因此当降级源自「会话列表不可渲染」（_list_recover_pending）时，此处必须真做修复（L2/L3）。

        退避不 sleep 长阻塞：只记录下次允许重启时刻，由 IDLE 每 10s 的巡检节奏自然隔开
        （长 sleep 会让 guard 主循环卡死判定（90s 无条件生效）误杀进程）。
        """
        try:
            if self._probe_browser():
                if not self._list_recover_pending:
                    return
                if self._recreate_pages():
                    self._list_recover_pending = False
                    self.log.info("列表不可渲染已自愈（L2/L3），退出 IDLE")
                    return
                raise ChannelError("window_missing", "会话列表仍不可渲染（页面重建未生效，保持 IDLE）")
        except ChannelError:
            raise
        except Exception:
            pass
        now_ts = time.time()
        today = datetime.now().date()
        if self._restart_day != today:
            self._restart_day = today
            self._restart_count = 0
            self._next_restart_at = 0.0
        if now_ts < self._next_restart_at:
            # 退避窗口内浏览器仍不可用：必须抛异常保持 IDLE——静默 return 会被状态机
            # 误判为「画面恢复」转 NORMAL，随即 SCAN 又失败，形成 SCAN↔IDLE 死循环
            # （2026-09-22 M3 vm101 实测：重启后每 15s 一轮刷屏 + 心跳 screenLocked 抖动）
            raise ChannelError("window_missing", "浏览器不可用（自动重启退避中，保持 IDLE）")
        if self._restart_count >= 3:
            raise ChannelError("window_missing", "浏览器不可用且当日自动重启已达上限（3 次），转人工")
        self._restart_count += 1
        self._next_restart_at = now_ts + min(60.0 * (2 ** (self._restart_count - 1)), 900.0)
        self.log.warning("浏览器探测失败：第 %d/3 次自动重启（%ds 后允许下一次）",
                         self._restart_count, int(self._next_restart_at - now_ts))
        try:
            self._restart_browser()
        except Exception as e:
            raise ChannelError("window_missing", f"浏览器重启失败: {e}")

    def stats_reset(self) -> None:
        self._stats = {"rows": 0, "opened": 0, "read": 0, "sent": 0,
                       "sent_image": 0, "sent_emoji": 0}

    def stats_summary(self) -> str:
        s = self._stats
        return (f"rows={s['rows']} opened={s['opened']} read={s['read']} sent={s['sent']}"
                f" image={s.get('sent_image', 0)} emoji={s.get('sent_emoji', 0)}")

    def capabilities(self) -> list[str]:
        """心跳能力声明：media 仅在 outbound 开关打开时声明（15 号文档）。"""
        caps = ["text"]
        if self.outbound_enabled:
            caps.extend(["image", "emoji"])
        return caps

    def _cleanup_outbox(self) -> None:
        """清理超过保留期的失败临时文件（成功文件在发送后即删）。"""
        cutoff = time.time() - self.cleanup_keep_failed_hours * 3600
        try:
            for path in self.outbox_dir.glob("media-*"):
                if path.is_file() and path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
        except OSError as e:
            self.log.warning("清理 outbox 失败: %s", e)

    # ------------------------------------------------------------------ 账号快照
    def read_accounts(self) -> list[dict]:
        """已登录账号快照（P1-2 真实登录态探测）。

        accounts.json 只作账号清单 + Cookie 源；每账号 ready() 聚合登录态：
        needLogin（Cookie 失效）账号从快照剔除；blocked（风控）账号置冷却后剔除。
        authorizeId 与 ui_ocr 对齐置 None——闲鱼 user_id 并非闲管家授权 ID，误报会
        污染 Java 侧账号主数据绑定（resolveAccountId 精确匹配），Java 退昵称匹配。
        """
        self._require_browser()
        self._reload_accounts()
        if not self.accounts:
            return []
        result = []
        for acc in self.accounts:
            if self.on_progress:
                self.on_progress()
            nick = (acc.get("shop_name") or str(acc.get("user_id") or "")).strip()
            if not nick:
                continue
            try:
                page = self._page_for({"userNick": nick})
            except ChannelError as e:
                if e.type == "window_missing":
                    raise
                if e.type == "blocked":
                    self._punish_until[nick] = datetime.now() + timedelta(seconds=self.punish_cooldown_sec)
                self.log.warning("账号 %s 页面不可用，快照剔除: %s", nick, e)
                continue
            except Exception as e:
                self.log.warning("账号 %s 页面打开失败，快照剔除: %s", nick, e)
                continue
            try:
                state = page.ready() or {}
            except Exception as e:
                self.log.warning("账号 %s ready() 探测失败，快照剔除: %s", nick, e)
                continue
            if state.get("needLogin") or state.get("blocked"):
                if state.get("blocked"):
                    self._punish_until[nick] = datetime.now() + timedelta(seconds=self.punish_cooldown_sec)
                self.log.warning("账号 %s 登录态失效（needLogin=%s blocked=%s），从快照剔除",
                                 nick, bool(state.get("needLogin")), bool(state.get("blocked")))
                continue
            result.append({"userNick": nick, "authorizeId": None, "loginStatus": "online"})
        self.log.info("账号快照: %s", [a["userNick"] for a in result])
        return result

    # ------------------------------------------------------------------ 读取
    def read_unread(self, account: dict, cursor) -> list[Message]:
        """读某账号下所有会话的新消息（门控/过滤语义移植 ui_ocr read_unread）。

        返回按会话游标过滤后的 in 消息（receiveTime 为校正前本地时间，状态机统一加时钟偏移）。
        """
        self._check_paused()
        user_nick = account["userNick"]
        now = datetime.now()
        # P1-3 风控冷却：期间静默跳过（自愈型，不映射 BLOCKED/window_missing）
        if now < self._punish_until.get(user_nick, datetime.min):
            return []
        try:
            page = self._page_for(account)
        except ChannelError as e:
            if e.type == "blocked":
                self._punish_until[user_nick] = now + timedelta(seconds=self.punish_cooldown_sec)
                self.log.warning("账号 %s 风控（非法访问），冷却 %ds 内不读", user_nick, self.punish_cooldown_sec)
                return []
            raise
        self._ensure_list(page)
        rows = self._normalize_rows(self._collect_rows(page))
        if not rows:
            # 2026-09-30 修复（vm101 静默漏读根因③）：rows=[] 有四态，须用探针区分——
            # 骨架屏停滞（行容器在、昵称未渲染）时页面**自身不会退出该状态**（实测 7.5h+），
            # 原实现只 raise window_missing 等下一轮 → 下一轮依旧骨架屏 → 死循环漏读。
            probe = self._probe_list(page, user_nick)
            if probe.get("needLogin"):
                raise ChannelError("login_required",
                                   f"账号 {user_nick} 会话列表空且页面出现登录特征（Cookie 失效）")
            # 自愈 L1：骨架屏 → 有冷却地软重载一次（成功则重新收集；失败交状态机 3 连 → IDLE）
            if self._is_skeleton(probe) and self._soft_reload_list(page, user_nick):
                rows = self._normalize_rows(self._collect_rows(page))
                if not rows:
                    probe = self._probe_list(page, user_nick)
            if not rows:
                self._list_recover_pending = True
                self.audit.event("list_not_ready", account=user_nick,
                                 convItems=probe.get("convItems"),
                                 nickRendered=probe.get("nickRendered"),
                                 skeleton=probe.get("skeleton"),
                                 softReload=self.list_recover_enable)
                raise ChannelError("window_missing",
                                   f"账号 {user_nick} 会话列表未渲染（行容器 {probe.get('convItems')} 个 / "
                                   f"昵称 {probe.get('nickRendered')} 个 / 骨架 {probe.get('skeleton')} 个）")
        self._list_recover_pending = False   # 本轮读到列表 → 无需 IDLE 巡检真修复（L2/L3）
        state = page.ready() or {}
        if state.get("needLogin"):
            raise ChannelError("login_required", f"账号 {user_nick} 页面出现登录特征（Cookie 失效）")
        if state.get("blocked"):
            self._punish_until[user_nick] = now + timedelta(seconds=self.punish_cooldown_sec)
            self.log.warning("账号 %s 页面提示非法访问（风控），冷却 %ds 内不读",
                             user_nick, self.punish_cooldown_sec)
            return []
        readable, first_round = _select_readable(rows, cursor, self._gate, now, self.log)

        messages: list[Message] = []
        flushed = 0  # P1-1：已即读即传条数（回调已接走，不在 messages 返回值里）
        for row in readable:
            buyer = row["buyerNick"]
            # 会话唯一 id（2026-09-24）：行级 sessionId 优先，缺失退 'noncid:<昵称>'
            conv_id = _conv_key(row)
            if self.on_progress:
                self.on_progress()  # 长列表逐个会话报进度（guard 判定「有进度即未卡死」）
            # P0-2（12 号文档 §5.1，D-12-4）：仅在会话边界检查中断，paused 优先。
            # 注意是 break 不是 throw：本账号已读消息的游标逐会话推进，抛异常会
            # 丢失已读消息且不可补报；正常返回已读部分，未读会话游标不动下轮续扫。
            if not self._is_paused() and self._interrupt_requested():
                self.log.info("回复任务到达：账号 %s 中断剩余会话读取（已读 %d 条先传，未读会话下轮门控续扫）",
                              user_nick, len(messages) + flushed)
                break
            c = cursor.conv(conv_id, buyer)
            forced_open = bool(row.get("_forced"))
            # T1.4：熔断黑名单跳过（TTL 到期自动出狱）；T1.2 兜底（forced_open）放行穿透——
            # 否则误熔断会话在 TTL 到期前无人重开，与候选排除叠加成死锁
            if (self.read_enable_blacklist and c.blacklisted_until
                    and now < c.blacklisted_until and not forced_open):
                continue
            is_new_conv = c.last_summary_hash is None
            try:
                self._open_verified(page, buyer, session_id=row.get("chat_id") or None)
                c.open_fail_streak = 0
                c.blacklist_strikes = 0      # 成功打开 → 重置累计
                c.blacklisted_until = None   # 对账重开成功 → 立即出狱，恢复正常门控
                self._stats["opened"] += 1
            except ChannelError as e:
                # sid_not_found（2026-09-28）：目标会话行本轮未在渲染窗口内定位到，属可恢复的
                # 瞬时缺失（虚拟滚动/刚点开他会话致顶部未渲染），不计熔断 strike，仅本轮跳过、
                # 游标不推进，下轮重试——避免把真实未读会话误关进黑名单。
                if e.type == "sid_not_found":
                    self.log.info("会话 sid 未定位（不计熔断，下轮重试）: %s（%s）", buyer, e)
                    continue
                # T1.4：打开失败计数；连续 open_fail_max 次熔断（伪会话白耗时间），
                # TTL 重复入狱翻倍、上限收敛 1h
                c.open_fail_streak += 1
                if self.read_enable_blacklist and c.open_fail_streak >= self.open_fail_max:
                    c.blacklist_strikes += 1
                    ttl = min(self.blacklist_ttl * (2 ** (c.blacklist_strikes - 1)),
                              timedelta(hours=1))
                    c.blacklisted_until = now + ttl
                    self.audit.event("conv_blacklisted", account=user_nick, buyer=buyer,
                                     streak=c.open_fail_streak, ttl_min=int(ttl.total_seconds() // 60))
                    self.log.warning("会话 %s 连续 %d 次打开失败，熔断 %d 分钟",
                                     buyer, c.open_fail_streak, int(ttl.total_seconds() // 60))
                else:
                    self.log.warning("打开会话失败（下轮重试，游标不推进）: %s（%s）", buyer, e)
                continue
            # 会话 id 优先取会话行 fiber 直采（2026-09-24，覆盖全量会话；WS 帧仅点开瞬时可达、
            # 命中率低作兜底）；buyer_id 仍以 DOM header 为准，header 缺时退会话行 userInfo.userId
            chat_id = (row.get("chat_id") or "").strip() or (
                self.frames.last_ids.get("chat_id") if self.frames else None)
            buyer_id = (self.frames.last_ids.get("buyer_id") if self.frames else None) or row.get("buyer_id")
            try:
                lines = self._read_lines(page, now)
            except TimeoutError as e:
                raise ChannelError("window_missing", f"读取会话 {buyer} 消息区 CDP 超时: {e}") from None
            # 13 号 D-13-1/D-13-6：会话绑定 = 可见商品卡中时间锚点最晚（最近）一张的
            # item_id；同一次打开内所有文本消息用同一绑定值（读取过程中不重算，防两轮
            # 绑定抖动导致判重漏判）。卡片行保留自己的 item_id（快照自包含）。
            # 同源顺带取该卡的价格/主图，作为**会话级商品快照**（图/价）随每条消息上报：
            # 管理端左栏每行缩略图与头部价格展示，item_id 为空（未关联商品）时也能看到商品。
            # 会话所属商品（2026-09-24 会话商品优先）：会话行 sessionInfo.itemInfo.itemId 为
            # 权威来源（未关联商品会话可空）；缺省时退「可见商品卡中时间锚点最晚一张」的
            # item_id（旧口径，兜底）。价格/主图仍取最近一张卡（会话行不含价格）。
            bind_item_id = (row.get("item_id") or "").strip()
            bind_price = ""
            bind_image = ""
            bind_t = None
            for ln in lines:
                if ln.get("kind") == "card" and ln.get("item_id"):
                    t = ln.get("receiveTime")
                    if t is not None and (bind_t is None or t > bind_t):
                        ext = ln.get("ext") or {}
                        bind_t = t
                        if not bind_item_id:
                            bind_item_id = ln["item_id"]
                        bind_price = (ext.get("price") or "").strip()
                        bind_image = (ext.get("imageUrl") or "").strip()
            for ln in lines:
                if ln.get("kind") != "card":
                    ln["item_id"] = bind_item_id
            # 会话行缩略图优先（列表阶段采得，对无卡片会话也有效）；否则退最近卡片主图
            snapshot_image = (row.get("product_img") or "").strip() or bind_image
            snapshot_price = bind_price
            snapshot_title = (row.get("item_title") or "").strip()
            fresh = _filter_fresh(lines, c, user_nick, buyer, now,
                                  first_round=first_round,
                                  has_unread=bool(row.get("unread")),
                                  summary_changed=bool(row.get("_summary_changed")),
                                  is_new_conv=is_new_conv,
                                  started_at=cursor.started_at, conv_id=conv_id)
            # 可观测：记录本会话是否真的被打开读取，以及读到的行数/过滤结果
            self.log.info("读取会话 %s：聊天区 %d 行（in %d），本轮回报 %d 条%s",
                          buyer, len(lines),
                          sum(1 for ln in lines if ln["direction"] == "in"), len(fresh),
                          f"（buyer_id={buyer_id}）" if buyer_id else "")
            if fresh:
                batch: list[Message] = []
                for m in fresh:
                    c.content_hashes.add(m["h"])
                    t = m["receive_time"]
                    if c.last_receive_time is None or t > c.last_receive_time:
                        c.last_receive_time = t
                    is_card = m.get("kind") == "card"
                    batch.append(Message(
                        client_msg_id="m-" + m["h"],
                        account_key={"userNick": user_nick, "authorizeId": account.get("authorizeId")},
                        buyer_id=buyer_id,
                        buyer_nick=buyer,
                        content=m["content"],
                        content_type="card" if is_card else m["content_type"],
                        receive_time=t.isoformat(timespec="seconds"),
                        raw={"src": "webim", "chat_id": chat_id},
                        chat_id=chat_id,
                        item_id=m.get("item_id"),
                        ext=m.get("ext"),
                        product_image_url=snapshot_image or None,
                        product_price_text=snapshot_price or None,
                        product_title=snapshot_title or None,
                    ))
                # P1-1（12 号文档 §六.1）即读即传：钩子已注入则本会话消息立即交状态机
                # flush（消除轮内尾部等待）；回调异常退轮回轮末累积不丢消息。
                # ui_ocr 不注入保持轮末统一返回（行为零变化）
                if self.on_messages:
                    try:
                        self.on_messages(batch)
                        flushed += len(batch)
                    except Exception:
                        self.log.exception("即读即传失败（退回累积，轮末 _flush_upload 重试）")
                        messages.extend(batch)
                else:
                    messages.extend(batch)
                self.audit.event("read", account=user_nick, buyer=buyer, unread=len(fresh),
                                 trigger="forced" if forced_open else "gate")
                self._stats["read"] += len(fresh)
            elif is_new_conv:
                # 新会话无可报消息：游标推进到 now，防后续重读历史
                c.last_receive_time = now
            if len(c.content_hashes) > 500:
                c.content_hashes.clear()  # 超上限清空：游标时间已推进，旧内容由 Java 判重兜底
            c.baselined = True
            c.last_summary_hash = row["summaryHash"]
            c.summary_hashes.add(row["summaryHash"])
            if len(c.summary_hashes) > 200:
                c.summary_hashes.clear()  # 超上限清空：仅影响变化检测，最坏后果是下轮多开一次
            c.last_unread_count = row.get("unread_count", 0)
            c.drained = len(fresh) == 0
            c.last_forced_open = now  # T1.2：兜底轮转选择按最近打开时刻排序
            # T1.1：登记本轮时间估计（max 防多线程覆盖抖动；解析失败退回归一化文本）
            t_est = row.get("time_est")
            if t_est is not None:
                if c.last_time_est is None or t_est > c.last_time_est:
                    c.last_time_est = t_est
            else:
                tn = _normalize(row.get("time_text") or "")
                if tn:
                    c.last_time_text_norm = tn
            # 读完导航回会话列表（下一行点击的前提）
            try:
                self._ensure_list(page)
            except TimeoutError as e:
                raise ChannelError("window_missing", f"复位会话列表 CDP 超时（{buyer}）: {e}") from None
        # T1.4：会话已从列表消失 → 清除熔断状态（下次出现重新尝试）
        if self.read_enable_blacklist:
            # 2026-09-24：会话是否仍存在按唯一 key 判（同一买家多会话不再互相掩盖）
            present = {_conv_key(r) for r in rows}
            for key, cc in cursor.convs.items():
                if key not in present and (cc.open_fail_streak or cc.blacklisted_until):
                    cc.open_fail_streak = 0
                    cc.blacklisted_until = None
                    cc.blacklist_strikes = 0
        self.log.info("账号 %s 扫描完成：%d 个会话，%d 条新消息%s",
                      user_nick, len(rows), len(messages) + flushed,
                      f"（其中 {flushed} 条已即读即传）" if flushed else "")
        return messages

    # ------------------------------------------------------------------ 发送
    def send_message(self, account: dict, buyer_nick: str, content: str,
                     item_id: str | None = None,
                     known_items: list[str] | None = None,
                     content_type: str = "text",
                     media: dict | None = None) -> SendResult:
        """发送 + DOM 验证（P1-4 身份闸门 + 气泡读回比对；headless 无截图，verify_image_path=None）。

        13 号文档 §五：item_id 非空 → 打开会话后比对会话可见商品卡 item 集合（弱校验）——
        任务 itemId ∉ 可见卡片集合、且 ∉ known_items（Java 下发的该买家×账号会话库中出现过的
        商品集合，13 号 §五 增强 B）→ 拒发「identity mismatch: item」；可见范围内无卡片 →
        放行（放行方向安全，不会误拒）。增强 B 修的多商品会话误拒：目标商品卡因不在可视窗口漏读时，
        只要该买家确实发过此商品（在库 known_items 内）即放行，仅当从未出现才拒。
        任务不带 item_id → 维持昵称匹配现状（P1-4 硬闸门不变）。

        15 号文档：content_type=image/emoji 时在身份/商品闸门通过后分派媒体发送；
        image 需要 media.url，emoji 需要 media.alt（缺省用 content 的 token）。
        """
        if content_type not in ("text", "image", "emoji"):
            return SendResult(False, f"unsupported_content_type: {content_type}")
        if content_type != "text" and not self.outbound_enabled:
            return SendResult(False, "outbound_disabled")
        if content_type != "text" and not self._media_ready(content_type, content, media):
            return SendResult(False, f"invalid_media_payload: {content_type}")
        user_nick = account["userNick"]
        page = None
        skip_reset = False
        try:
            page = self._page_for(account)
            self._ensure_list(page)
            # 2. 打开会话 + header 身份校验（P1-4 硬闸门：不一致 → 不发送、回传 failed）
            self._open_verified(page, buyer_nick)
            # 2.5 商品定位弱校验（13 号 §五 + 增强 B）：读卡片失败视为可见范围不可判定 → 放行
            if item_id:
                try:
                    lines = self._read_lines(page, datetime.now())
                except Exception:
                    lines = []
                visible_items = {ln["item_id"] for ln in lines
                                 if ln.get("kind") == "card" and ln.get("item_id")}
                # 增强 B：并入库中该会话已知商品集合，目标卡不在可视窗口但买家发过此商品 → 放行
                allow = set(visible_items) | {str(x) for x in (known_items or [])}
                if visible_items and str(item_id) not in allow:
                    return SendResult(False, "identity mismatch: item")
            if content_type != "text":
                result = self._send_media(page, user_nick, content_type, content, media)
                # 图片校验不确定（None）时跳过导航复位：复位可能打断仍在进行的上传，
                # 把「可能已发出」变成「确实没发出」。下一轮 SCAN 入口会再次 _ensure_list。
                if content_type == "image" and result.ok is None:
                    skip_reset = True
                return result
            # 3/4. 写入输入框 + 验证（失败重试 1 次）
            prepared = page.prepare_text(content)
            if not self._prepared_ok(prepared, content):
                prepared = page.prepare_text(content)
                if not self._prepared_ok(prepared, content):
                    return SendResult(False, "写入验证失败：输入框未见回复内容，已中止不发送")
            # 5/6. 点发送 + 读回最后几条消息验证新气泡
            sent = page.send_text(content)
            ok, detail = self._verdict(sent, content)
            if ok is True:
                self._stats["sent"] += 1
                return SendResult(True, detail, verify_image_path=None)
            if ok is None:
                return SendResult(None, detail)
            return SendResult(False, detail)
        except ChannelError as e:
            if e.type == "blocked":
                self._punish_until[user_nick] = datetime.now() + timedelta(seconds=self.punish_cooldown_sec)
                return SendResult(False, f"风控冷却（非法访问），本轮不发送: {e.detail}")
            return SendResult(False, f"{e.type}: {e.detail}")
        except TimeoutError as e:
            # CDP 调用超时（2026-09-22 vm101 根因防护）：回传 failed 不静默丢任务
            return SendResult(False, f"CDP 调用超时（任务中止，不发送）: {e}")
        finally:
            # 导航复位（评审 P2-2）：回到会话列表，下一轮 SCAN 才不会把聊天区当列表读
            if self._is_paused():
                self.log.info("人工暂停中，跳过 EXEC 导航复位")  # R5：不抢窗口 + 不误报复位失败
            elif page is not None and not skip_reset:
                try:
                    self._ensure_list(page)
                except ChannelError:
                    self.log.warning("EXEC 导航复位失败（下一轮 SCAN 会重新定位）")
                except TimeoutError:
                    self.log.warning("EXEC 导航复位 CDP 超时（下一轮 SCAN 会重新定位）")

    # ------------------------------------------------------------------ 内部：媒体外发
    @staticmethod
    def _media_ready(content_type: str, content: str, media: dict | None) -> bool:
        if content_type == "image":
            return isinstance(media, dict) and bool(str(media.get("url") or "").strip())
        if content_type == "emoji":
            return isinstance(media, dict) and bool(str(media.get("alt") or content or "").strip())
        return True

    def _send_media(self, page, user_nick: str, content_type: str, content: str,
                    media: dict | None) -> SendResult:
        """媒体任务串行 + 同账号最小间隔（状态机本身单线程，间隔防高频发图风控）。"""
        last = self._media_last_at.get(user_nick, 0.0)
        wait = self.account_min_interval_sec - (time.time() - last)
        if wait > 0:
            time.sleep(wait)
        self._media_last_at[user_nick] = time.time()
        if content_type == "image":
            return self._send_image(page, media or {})
        return self._send_emoji(page, content, media or {})

    def _send_image(self, page, media: dict) -> SendResult:
        url = str(media.get("url") or "").strip()
        path, error = self._download_media(url)
        if path is None:
            return SendResult(False, f"image_download_failed: {error}")
        try:
            before_toasts = Counter(page.toasts())
        except Exception:
            before_toasts = Counter()
        sent_ok = False
        try:
            before = self._out_media_snapshot(page, "image")
            injected = page.upload_image(str(path))
            if not injected.get("ok"):
                return SendResult(False, f"image_inject_failed: {injected.get('error')}")
            deadline = time.time() + self.verify_timeout_sec
            placeholder_seen = False
            while time.time() < deadline:
                time.sleep(1.0)
                try:
                    current = Counter(page.toasts())
                except Exception:
                    current = Counter()
                # 只判定注入后新增的 toast，避免上一次失败 toast 未消失导致误报 failed
                new_toasts = [text for text, count in current.items()
                              if count > before_toasts.get(text, 0)]
                bad = self._media_error_toast(new_toasts)
                if bad:
                    if "上传图片失败" in bad:
                        return SendResult(
                            False,
                            f"image_upload_auth_failed: {bad}；"
                            "请检查 accounts.json 新鲜度/重新登录（export-accounts）")
                    return SendResult(False, f"image_upload_failed: {bad}")
                try:
                    events = page.out_media_events()
                except Exception:
                    events = []
                for event in events:
                    if event.get("content_type") != "image":
                        continue
                    key = self._media_event_key(event)
                    if key in before:
                        continue
                    if self._event_remote_url(event):
                        sent_ok = True
                        self._stats["sent_image"] += 1
                        return SendResult(True, "发送成功（聊天区验证到图片气泡）")
                    placeholder_seen = True
            if placeholder_seen:
                return SendResult(False, "image_upload_failed: 仅出现本地占位图，未读到远程图片")
            return SendResult(None, "图片已注入但未读到 out 图片（不重发，交 Java 超时兜底）")
        finally:
            if sent_ok:
                path.unlink(missing_ok=True)

    def _send_emoji(self, page, content: str, media: dict) -> SendResult:
        alt = str(media.get("alt") or content or "").strip()[:500]
        if not alt:
            return SendResult(False, "emoji_missing_alt")
        before = self._out_media_snapshot(page, "emoji")
        prepared = page.prepare_text(alt)
        if (not isinstance(prepared, dict) or prepared.get("error")
                or alt not in str(prepared.get("value") or "")):
            return SendResult(False, "写入验证失败：输入框未见表情 token，已中止不发送")
        sent = page.send_text(alt)
        if not isinstance(sent, dict) or sent.get("error"):
            err = sent.get("error") if isinstance(sent, dict) else "无返回"
            return SendResult(False, f"未找到「发送」按钮（{err}），可能不在聊天页")
        deadline = time.time() + min(self.verify_timeout_sec, 20)
        while time.time() < deadline:
            try:
                events = page.out_media_events()
            except Exception:
                events = []
            for event in events:
                if event.get("content_type") != "emoji":
                    continue
                if self._media_event_key(event) in before:
                    continue
                event_alt = self._emoji_alt(event)
                if not event_alt:
                    continue
                # 空 alt 绝不能参与包含判定（"" in alt 恒真）；反向包含加最小长度门槛
                if alt in event_alt or (len(event_alt) >= 4 and event_alt in alt):
                    self._stats["sent_emoji"] += 1
                    return SendResult(True, "发送成功（聊天区验证到表情气泡）")
            time.sleep(1.0)
        return SendResult(None, "已点发送但未读到 out 表情气泡（不重发，交 Java 兜底）")

    def _download_media(self, url: str):
        """下载并校验出站图片：https/白名单/公网 IP/不跟随重定向/魔数/大小。"""
        normalized = _media_url(url)
        if not normalized:
            return None, "invalid_url"
        host = urlsplit(normalized).hostname or ""
        if not self._public_host(host):
            return None, "private_or_unresolved_host"
        allowed = {"jpeg" if f in ("jpg", "jpeg") else f for f in self.allowed_formats}
        name = f"media-{uuid.uuid4().hex}"
        partial = self.outbox_dir / (name + ".part")
        try:
            with requests.get(normalized, stream=True, timeout=self.download_timeout_sec,
                              allow_redirects=False,
                              headers={"User-Agent": APP_UA}) as resp:
                if resp.status_code != 200:
                    return None, f"http_{resp.status_code}"
                length = resp.headers.get("Content-Length")
                if length and int(length) > self.image_max_bytes:
                    return None, "too_large"
                total = 0
                with open(partial, "wb") as fh:
                    for chunk in resp.iter_content(64 * 1024):
                        if not chunk:
                            continue
                        total += len(chunk)
                        if total > self.image_max_bytes:
                            return None, "too_large"
                        fh.write(chunk)
            with open(partial, "rb") as fh:
                head = fh.read(16)
            ext = self._magic_ext(head)
            if ext not in allowed:
                return None, f"unsupported_format:{ext or 'unknown'}"
            suffix = ".jpg" if ext == "jpeg" else f".{ext}"
            final = self.outbox_dir / f"{name}{suffix}"
            os.replace(partial, final)
            return final, ""
        except requests.RequestException as e:
            return None, f"{type(e).__name__}: {e}"
        except Exception as e:
            return None, f"{type(e).__name__}: {e}"
        finally:
            partial.unlink(missing_ok=True)

    @staticmethod
    def _magic_ext(head: bytes) -> str | None:
        if head.startswith(b"\xff\xd8\xff"):
            return "jpeg"
        if head.startswith(b"\x89PNG\r\n\x1a\n"):
            return "png"
        if head[:6] in (b"GIF87a", b"GIF89a"):
            return "gif"
        if head.startswith(b"BM"):
            return "bmp"
        return None

    @staticmethod
    def _public_host(host: str) -> bool:
        try:
            infos = socket.getaddrinfo(host, None)
        except OSError:
            return False
        for info in infos:
            try:
                ip = ipaddress.ip_address(info[4][0])
            except ValueError:
                return False
            if (ip.is_private or ip.is_loopback or ip.is_link_local
                    or ip.is_reserved or ip.is_multicast or ip.is_unspecified):
                return False
        return True

    def _out_media_snapshot(self, page, kind: str) -> set[str]:
        try:
            events = page.out_media_events()
        except Exception:
            return set()
        return {self._media_event_key(e) for e in events if e.get("content_type") == kind}

    @staticmethod
    def _media_event_key(event: dict) -> str:
        return json.dumps({k: event.get(k) for k in
                           ("content_type", "source_message_id", "timestamp", "items")},
                          ensure_ascii=False, sort_keys=True)

    @staticmethod
    def _event_remote_url(event: dict) -> bool:
        for item in event.get("items") or []:
            url = item.get("url") or ""
            if url.startswith("http://") or url.startswith("https://") or url.startswith("//"):
                return True
        return False

    @staticmethod
    def _emoji_alt(event: dict) -> str:
        for item in event.get("items") or []:
            alt = item.get("alt")
            if isinstance(alt, str) and alt.strip():
                return alt.strip()
        return ""

    @staticmethod
    def _media_error_toast(toasts: list[str]) -> str | None:
        for text in toasts:
            if any(key in text for key in ("上传图片失败", "图片大小", "只能上传", "上传失败")):
                return text[:200]
        return None

    # ------------------------------------------------------------------ 内部：浏览器与页面
    def _ensure_browser(self) -> None:
        if self.browser is None:
            b = Browser(port=self.port, headless=self.headless,
                        profile_dir=self.profile_dir or None,
                        browser_path=self.browser_path or None, log=self.log)
            b.start()
            self.browser = b
            self.frames = WsFrames(b.client)

    def _probe_browser(self) -> bool:
        """探测调试口可用性（纯 HTTP，不依赖本地 Browser 对象状态）。

        注意不能因 self.browser is None 直接返回 False：进程重启后可能已有孤儿浏览器
        监听端口（重启 Agent 时只杀了 pythonw，浏览器仍在跑），此时端口可用但本地未
        连接——应由 _require_browser 探测成功后 connect 复用（2026-09-22 M3 vm101
        实测：孤儿浏览器锁 profile，新进程 warmup 启动新实例必失败，死循环 IDLE）。
        """
        try:
            http_json(f"http://127.0.0.1:{self.port}/json/version", timeout=5.0)
            return True
        except Exception:
            return False

    def _require_browser(self) -> None:
        if not self._probe_browser():
            raise ChannelError("window_missing", "浏览器调试口不可用（进程死亡或未启动）")
        if self.browser is None:
            # 端口可用但本地未连接：浏览器由先前进程启动（本进程重启接管）→ connect 复用，
            # 避免 _ensure_browser 再启新实例与现有实例抢 profile/端口
            b = Browser(port=self.port, headless=self.headless,
                        profile_dir=self.profile_dir or None,
                        browser_path=self.browser_path or None, log=self.log)
            b.connect()
            self.browser = b
            self.frames = WsFrames(b.client)
            self.log.info("已复用运行中的浏览器（调试口 %s）", self.port)

    def _restart_browser(self) -> None:
        """重启浏览器 + rehydration（P1-5）：清页面 → 关浏览器（含残留进程树清理）→
        重启 → 全量重建账号页面（context/Cookie 注入/页面打开）。"""
        self._dispose_pages()
        if self.browser is not None:
            try:
                self.browser.close()
            except Exception:
                pass
            self.browser = None
        self.frames = None
        self._ensure_browser()
        for acc in self.accounts:
            if self.on_progress:
                self.on_progress()
            nick = (acc.get("shop_name") or str(acc.get("user_id") or "")).strip()
            if not nick:
                continue
            try:
                self._page_for({"userNick": nick})
            except Exception as e:
                self.log.warning("账号 %s rehydration 失败（下轮 read_accounts 会再试）: %s", nick, e)

    # ------------------------------ 列表不可渲染自愈阶梯（L2/L3，2026-09-30）
    def _recreate_pages(self) -> bool:
        """L2 逐账号重建页面；仍停滞则 L3 全量重建浏览器。任一环节都有探针验证。

        L2 = close（含 close_page + dispose_context）→ _page_for 重开（新 context / Cookie
        重注入 / navigate），成本约一次页面打开；L3 = _restart_browser()（沿用 health_check
        的日限 3 次与指数退避），最后兜底。
        """
        nicks = [a["userNick"] for a in (self.accounts or []) if a.get("userNick")]
        if not nicks:
            return False
        ok = True
        for nick in nicks:
            if self.on_progress:
                self.on_progress()
            if not self._recreate_page(nick):
                ok = False
        if ok:
            self.log.info("页面重建后会话列表恢复渲染（L2）")
            return True
        self.log.warning("L2 页面重建未恢复，升级 L3 全量重建浏览器")
        try:
            self._restart_browser()
        except Exception as e:
            self.log.warning("L3 全量重建浏览器失败: %s", e)
            return False
        return self._pages_renderable()

    def _recreate_page(self, nick: str) -> bool:
        """单账号页面重建 + 探针验证；列表本就为空（无会话）也算通过。"""
        old = self._pages.pop(nick, None)
        if old is not None:
            try:
                old.close()
            except Exception:
                pass
        try:
            page = self._page_for({"userNick": nick})
            probe = self._probe_list(page, nick)
        except Exception as e:
            self.log.warning("账号 %s 页面重建失败: %s", nick, e)
            return False
        if int(probe.get("convItems") or 0) == 0:
            return True                      # 列表本就为空（无会话）：不算停滞
        return int(probe.get("nickRendered") or 0) > 0

    def _pages_renderable(self) -> bool:
        """全部常驻页面昵称已渲染（或列表本就为空）才算恢复。"""
        for nick, page in list(self._pages.items()):
            probe = self._probe_list(page, nick)
            if self._is_skeleton(probe):
                return False
        return True

    def _dispose_pages(self) -> None:
        for page in list(self._pages.values()):
            try:
                page.close()
            except Exception:
                pass
        self._pages.clear()

    def _reload_accounts(self) -> None:
        """重读 accounts.json：运维更新 Cookie 后无需重启进程即可恢复（BLOCKED 恢复路径）。

        文件缺失/为空时沿用 warmup 结果；不触发调试口导出（那是启动时一次性降级，
        每轮导出会反复破坏闲管家加密层且自身短时不可用）。
        """
        fresh = load_accounts_file(self.accounts_file)
        if fresh and fresh != self.accounts:
            self.log.info("accounts.json 已更新（%d 个账号），重建页面缓存", len(fresh))
            self.accounts = fresh
            self._dispose_pages()

    def _page_for(self, acc: dict) -> AccountPage:
        """userNick → 常驻 AccountPage；不存在则从账号清单取全量信息打开。

        打开失败按登录态映射：needLogin → login_required（BLOCKED 等人工导 Cookie）；
        blocked（非法访问）→ 通道内部信号 "blocked"（账号级冷却，不冒泡状态机）；
        其他 → login_required（Cookie 大概率失效）。
        """
        nick = (acc.get("userNick") or acc.get("shop_name") or "").strip()
        if not nick:
            raise ChannelError("account_missing", "账号昵称为空")
        page = self._pages.get(nick)
        if page is not None:
            return page
        full = next((a for a in (self.accounts or [])
                     if (a.get("shop_name") or str(a.get("user_id") or "")).strip() == nick), None)
        if full is None:
            raise ChannelError("account_missing", f"账号清单中无「{nick}」（accounts.json 未含该账号）")
        page = AccountPage(self.browser, full, self.log)
        try:
            ok = page.open(ready_timeout=self.ready_timeout, retries=self.open_retries,
                           progress=self.on_progress)
        except RuntimeError as e:
            page.close()
            raise ChannelError("blocked", f"页面打开被拒（非法访问/UA 伪装未生效）: {e}")
        except TimeoutError as e:
            page.close()
            raise ChannelError("login_required", f"页面打开 CDP 超时（{nick}）: {e}") from None
        if not ok:
            state = {}
            try:
                state = page.ready() or {}
            except Exception:
                pass
            page.close()
            if state.get("needLogin"):
                raise ChannelError("login_required",
                                   f"账号 {nick} 页面未就绪且出现登录特征（Cookie 失效）")
            if state.get("blocked"):
                raise ChannelError("blocked", f"账号 {nick} 页面提示非法访问（风控，自愈型）")
            raise ChannelError("login_required",
                               f"账号 {nick} 页面未就绪（{self.ready_timeout}s×{self.open_retries} 重试仍无会话列表）")
        self._pages[nick] = page
        return page

    # ------------------------------------------------------------------ 内部：会话列表
    def _ensure_list(self, page) -> None:
        """确保页面停在会话列表（读/发送的起点与复位终点）。

        优先 BACK_JS 点返回；未命中（或无返回按钮）→ Page.navigate 重载兜底。
        """
        state = page.ready() or {}
        if state.get("hasList"):
            return
        data = {}
        raw = page.browser.client.evaluate(BACK_JS, session_id=page.session_id)
        try:
            data = json.loads(raw) if isinstance(raw, str) else (raw or {})
        except json.JSONDecodeError:
            data = {}
        if data.get("clicked"):
            deadline = time.time() + 15.0
            while time.time() < deadline:
                time.sleep(1.0)
                state = page.ready() or {}
                if state.get("hasList"):
                    return
            self.log.warning("BACK_JS 已点击但列表未就绪，改用导航兜底")
        page.browser.client.call_session(page.session_id, "Page.navigate",
                                         {"url": page.account["webview_src"]})
        deadline = time.time() + self.ready_timeout
        while time.time() < deadline:
            time.sleep(2.0)
            if self.on_progress:
                try:
                    self.on_progress()
                except Exception:
                    pass
            state = page.ready() or {}
            if state.get("hasList"):
                return
        raise ChannelError("window_missing", "无法回到会话列表（BACK_JS 与导航兜底均失败）")

    def _collect_rows(self, page, max_steps: int | None = None) -> list[dict]:
        """滚动循环 Python 化（P2-3）：STEP_COLLECT_JS 每步一次 evaluate，
        步间 on_progress + paused_check（单个长 Promise 会吞掉回调、被 guard 误判卡死）。

        双向扫描：up 相滚到顶转 down 相；down 相到底或连续 3 步无新行早停
        （列表按时间排序，覆盖全列表即够）。raw 文本去重（同 COLLECT_JS 语义）。
        """
        max_steps = max_steps or self.collect_steps
        seen: dict[str, dict] = {}
        phase = "up"
        down_stale = 0
        err_streak = 0
        steps = 0
        while steps < max_steps:
            self._check_paused()
            # P0-2（12 号文档）：列表收集中允许抛式中断（尚未读任何会话，游标无损；
            # 本轮放弃下轮从头收）。paused 已在上一行优先拦截
            if self._interrupt_requested():
                raise ChannelError("interrupted", "回复任务到达，中止会话列表收集（游标无损，下轮重扫）")
            if self.on_progress:
                self.on_progress()
            try:
                raw = page.browser.client.evaluate(
                    STEP_COLLECT_JS % {"phase": json.dumps(phase)}, session_id=page.session_id)
            except TimeoutError as e:
                # 单步 evaluate 超时：同 holder 不可用处理（等一拍重试，不推进 step）
                self.log.warning("收集会话列表 CDP 超时（第 %d 步）: %s", steps, e)
                err_streak += 1
                if err_streak >= 10:
                    self.log.warning("会话列表 CDP 持续超时，提前结束扫描")
                    break
                time.sleep(1.0)
                continue
            try:
                data = json.loads(raw) if isinstance(raw, str) else (raw or {})
            except json.JSONDecodeError:
                data = {}
            if data.get("error"):
                err_streak += 1
                if err_streak >= 10:
                    self.log.warning("会话列表 holder 持续不可用（%s），提前结束扫描", data["error"])
                    break
                time.sleep(1.0)  # holder 重建窗口：等一拍重试（不推进 step）
                continue
            err_streak = 0
            new_rows = 0
            for row in data.get("rows") or []:
                key = row.get("raw") or ""
                if key and key not in seen:
                    seen[key] = row
                    new_rows += 1
            if phase == "up":
                if data.get("atTop"):
                    phase = "down"
            else:
                if data.get("atBottom"):
                    break
                if new_rows == 0:
                    down_stale += 1
                    if down_stale >= 3:
                        break
                else:
                    down_stale = 0
            steps += 1
            time.sleep(0.6)  # 与 COLLECT_JS 步进节奏一致（React 渲染等待）
        self._stats["rows"] += len(seen)
        return list(seen.values())

    # ------------------------------ 列表不可渲染自愈（2026-09-30 vm101 漏读根因③）
    @staticmethod
    def _is_skeleton(probe: dict) -> bool:
        """行容器已渲染但昵称全未渲染 → 骨架屏/React 列表重建停滞（vm101 09-30 实测特征）。"""
        return int(probe.get("convItems") or 0) > 0 and int(probe.get("nickRendered") or 0) == 0

    def _probe_list(self, page, user_nick: str) -> dict:
        """列表渲染探针（自带兜底）：探测失败退空字典，由调用方按「未渲染」处理。"""
        try:
            return page.probe_list() or {}
        except Exception as e:
            self.log.warning("账号 %s 列表探针失败: %s", user_nick, e)
            return {}

    def _soft_reload_list(self, page, user_nick: str) -> bool:
        """L1 自愈：软重载会话列表页并等渲染，昵称渲染出来才算成功。

        冷却 list_recover_cooldown_sec（默认 300s）：频繁 navigate 会触发闲鱼风控
        （punish/非法访问），冷却期内不重复重载 → 直接返回 False，交状态机 3 连失败进 IDLE，
        由 health_check 走 L2/L3。等待期间每 2s 回调 on_progress：ready_timeout 可达 150s，
        不回调会被 guard main_loop_stale_sec 误判主循环卡死杀进程。
        """
        if not self.list_recover_enable:
            return False
        now_ts = time.time()
        if now_ts < self._list_recover_at.get(user_nick, 0.0) + self.list_recover_cooldown_sec:
            self.log.warning("账号 %s 列表停滞，软重载冷却中（%ds 内不重复导航）",
                             user_nick, self.list_recover_cooldown_sec)
            return False
        self._list_recover_at[user_nick] = now_ts
        self.log.warning("账号 %s 会话列表停滞（骨架屏），执行软重载自愈", user_nick)
        self.audit.event("list_soft_reload", account=user_nick)
        try:
            page.browser.client.call_session(page.session_id, "Page.navigate",
                                             {"url": page.account["webview_src"]})
        except Exception as e:
            self.log.warning("账号 %s 软重载失败: %s", user_nick, e)
            return False
        deadline = time.time() + self.list_recover_timeout_sec
        while time.time() < deadline:
            self._check_paused()
            if self.on_progress:
                try:
                    self.on_progress()
                except Exception:
                    pass
            probe = self._probe_list(page, user_nick)
            if int(probe.get("nickRendered") or 0) > 0:
                self.log.info("账号 %s 软重载后会话列表已渲染", user_nick)
                self.audit.event("list_soft_reload_ok", account=user_nick)
                return True
            if probe.get("needLogin") or probe.get("blocked"):
                return False
            time.sleep(2.0)
        self.log.warning("账号 %s 软重载后 %ds 内仍未渲染会话列表",
                         user_nick, int(self.list_recover_timeout_sec))
        return False

    def _normalize_rows(self, rows: list[dict]) -> list[dict]:
        """收集到的会话行 → 门控所需格式（buyerNick/summaryHash/unread/time_est + 会话身份）。"""
        out = []
        for row in rows:
            nick = (row.get("nick") or "").strip()
            if not nick:
                continue
            summary = _row_summary(row)
            # P0-1（12 号文档 §4.3）：假昵称防御校验——JS 侧骨架屏守卫之外的兜底
            # （昵称节点缺失时 parts[0] 落到预览等残余形态）：命中即跳过该行并记审计，
            # 防假会话消耗打开尝试并触发 T1.4 熔断
            if nick == summary:
                self.audit.event("row_skip", reason="nick_eq_summary", nick=nick[:30])
                continue
            if len(nick) > 40:
                self.audit.event("row_skip", reason="nick_too_long", nick=nick[:30])
                continue
            if any(kw in nick for kw in self.conv_ignore):
                self.audit.event("row_skip", reason="ignore_keyword", nick=nick[:30])
                continue
            if nick in _PSEUDO_NICKS:
                # 订单/评价状态标签被误当昵称（内置拦截，不依赖各 VM 配置）
                self.audit.event("row_skip", reason="pseudo_nick", nick=nick[:30])
                continue
            time_text = (row.get("time_text") or "").strip()
            out.append({
                "buyerNick": nick,
                "summary": summary,
                "summaryHash": _sha1(nick + "|" + _hash_norm(summary)),
                "unread": bool(row.get("unread_text")),
                "unread_count": row.get("unread_count") or 0,
                "time_est": parse_time_text(time_text) if time_text else None,
                "time_text": time_text,
                # —— 会话唯一身份（2026-09-24）：browser.STEP_COLLECT_JS 从会话行 fiber 直采 ——
                # chat_id = 会话唯一 id（sessionInfo.sessionId，无需点开会话，覆盖全量会话）
                # item_id/item_title = 会话所属商品（修复「未关联商品」；会话商品优先）
                # buyer_id = 买家 id（userInfo.userId，绕开昵称脱敏）
                "chat_id": (row.get("session_id") or "").strip(),
                "item_id": (row.get("item_id") or "").strip(),
                "item_title": (row.get("item_title") or "").strip()[:255],
                "buyer_id": (row.get("buyer_id") or "").strip(),
                # 会话级商品快照-主图：会话行 fiber itemInfo.mainPic 优先，退 DOM 行缩略图
                "product_img": (row.get("item_pic") or "").strip() or (row.get("product_img") or "").strip(),
                "raw": (row.get("raw") or "")[:200],
            })
        return out

    # ------------------------------------------------------------------ 内部：会话打开与读取
    def _open_verified(self, page, buyer: str, max_steps: int = 40, attempts: int = 3,
                       session_id: str | None = None) -> str:
        """打开会话 + header 身份校验（P1-4）：多行命中时逐行点开验证，全不匹配才 buyer_missing。

        2026-09-24 会话唯一性：session_id 非空时按会话唯一 id（fiber sessionInfo.sessionId）
        精确定位行为（同一买家多商品会话昵称相同，仅昵称只能命中第一行）；点开后对 header
        回传的 activeCid 做一致性校验（不一致 = 点错行 → 视为未确认，换行重试）。

        2026-09-28 修复：session_id 非空时改走 _open_by_session_id 单次精确路径——OPEN_JS 已
        取消昵称降级，若列表滚动耗尽仍找不到该 sid 行则报 sid_not_found（不计熔断 strike），
        不再反复点同买家兄弟会话触发误熔断。

        点开即先 pump WS 帧——聊天页加载的 cid/pairSecond 帧紧贴点击到达，错过即丢
        （CDP.call 响应循环会吞事件消息，帧收集只能在无并发调用窗口主动 recv）。
        """
        if session_id:
            return self._open_by_session_id(page, buyer, max_steps, session_id)
        for skip in range(attempts):
            self._check_paused()
            # 每步 touch：open_conversation 是单次长 evaluate（40 步滚动在 JS 侧），
            # 期间无回调；3 次重试叠加 >90s 会触发 guard 卡死误杀（2026-09-22 vm101 实测）
            if self.on_progress:
                self.on_progress()
            try:
                result = page.open_conversation(buyer, max_steps=max_steps, settle_seconds=0.0,
                                                skip=skip, session_id=session_id)
            except TimeoutError as e:
                # CDP 调用超时（页面 Promise 不 resolve / timer 节流）：转 window_missing
                # 走「打开会话失败，下轮重试」路径（游标不推进），不再永久卡死
                raise ChannelError("window_missing", f"打开会话 CDP 超时（{buyer}）: {e}") from None
            if not result.startswith("clicked"):
                raise ChannelError("buyer_missing", f"会话列表未找到买家「{buyer}」")
            if self.on_progress:
                self.on_progress()
            # clear() 会重置 last_ids（含上一会话 buyer_id），失败时需回滚快照：
            # 否则错误行点击 → header 未确认 → last_ids 被清空后保持 null，
            # 观测字段恒为 null（vm101 重测实测：报告 ws_ids.buyer_id=null 而日志已抓到）。
            snapshot = dict(self.frames.last_ids) if self.frames else None
            if self.frames:
                self.frames.clear()
                self.frames.pump(3.0)  # P1-2（12 号文档 §六.2）：5s→3s，cid/pairSecond 帧紧贴点击到达，3s 足够
            page.wait_for_messages(5.0)  # P1-2：10s→5s（实现为首条消息即返回的轮询，收紧超时即可）
            probe = self._header_probe(page, buyer)
            # 会话唯一 id 校验（2026-09-24）：目标 session_id 非空且页面回传 activeCid 非空且
            # 二者不一致 → 点开的是同一买家另一个商品的会话（昵称回退路径点错行）→ 视为未
            # 确认，走下方「回列表换行重试」；activeCid 读不到（空）时维持昵称校验放行（旧行为）
            if probe and session_id:
                cid = str(probe.get("activeCid") or "").strip()
                if cid and cid != str(session_id):
                    self.log.warning("会话 activeCid=%s ≠ 目标 sessionId=%s（同名多会话点错行，第 %d 个命中行）",
                                     cid, session_id, skip + 1)
                    probe = None
            if probe:
                bid = probe.get("buyerId")
                if bid and self.frames:
                    # buyer_id 以 DOM「闲鱼号」链接为准（比 WS 帧稳定且无我方/对方歧义）
                    self.frames.last_ids["buyer_id"] = bid
                return result
            if self.on_progress:
                self.on_progress()
            # 身份未确认：本会话不可信，恢复上一次成功会话的身份记录
            if snapshot is not None and self.frames:
                self.frames.last_ids = snapshot
            self.log.warning("打开行文本含「%s」但会话页 header 未确认身份（第 %d 个命中行），回列表重试",
                             buyer, skip + 1)
            try:
                self._ensure_list(page)
            except TimeoutError as e:
                raise ChannelError("window_missing", f"回列表 CDP 超时（{buyer}）: {e}") from None
        raise ChannelError("buyer_missing",
                           f"会话列表命中 {attempts} 行但 header 身份校验均未通过（{buyer}）")

    def _open_by_session_id(self, page, buyer: str, max_steps: int, session_id: str) -> str:
        """session_id 精确打开路径（2026-09-28）：单次滚动定位目标 sid 行并点开。

        不再按昵称降级点同买家兄弟会话。找不到目标行 / 点开后 activeCid 与目标不符 / header
        未确认 → 统一报 sid_not_found（上层 read 循环不计入熔断 strike，仅本轮跳过、下轮重试）。
        """
        self._check_paused()
        if self.on_progress:
            self.on_progress()
        try:
            # SID 路径 skip 无意义（OPEN_JS 仅按 sid 匹配）；max_steps 适当放宽以扫到列表顶部
            result = page.open_conversation(buyer, max_steps=max(60, max_steps), settle_seconds=0.0,
                                            skip=0, session_id=session_id)
        except TimeoutError as e:
            raise ChannelError("window_missing", f"打开会话 CDP 超时（{buyer}）: {e}") from None
        if result.startswith("sid-not-found"):
            raise ChannelError("sid_not_found",
                               f"列表内未定位到会话 sid={session_id}（{buyer}），本轮跳过不熔断")
        if result.startswith("no-list-holder"):
            raise ChannelError("window_missing", f"会话列表 holder 丢失（{buyer}）")
        if not result.startswith("clicked"):
            raise ChannelError("sid_not_found",
                               f"打开会话 sid={session_id} 返回非 clicked：{result[:60]}")
        if self.on_progress:
            self.on_progress()
        snapshot = dict(self.frames.last_ids) if self.frames else None
        if self.frames:
            self.frames.clear()
            self.frames.pump(3.0)
        page.wait_for_messages(5.0)
        probe = self._header_probe(page, buyer)
        # activeCid 一致性双保险：精确点中的行开出的会话 cid 应 == 目标 sid；不符说明没真开对
        cid = str((probe or {}).get("activeCid") or "").strip()
        if probe and cid and cid != str(session_id):
            self.log.warning("按 sid 点开后 activeCid=%s ≠ 目标 sessionId=%s（视为未确认，不熔断）",
                             cid, session_id)
            probe = None
        if probe:
            bid = probe.get("buyerId")
            if bid and self.frames:
                self.frames.last_ids["buyer_id"] = bid
            return result
        if snapshot is not None and self.frames:
            self.frames.last_ids = snapshot
        raise ChannelError("sid_not_found",
                           f"按 sid={session_id} 点开会话但 header 未确认身份（{buyer}），本轮跳过不熔断")

    def _header_probe(self, page, nick: str) -> dict | None:
        """聊天区 header 身份探针（P1-4）：返回 {header, hasNick, buyerId}；未确认返回 None。"""
        try:
            raw = page.browser.client.evaluate(HEADER_JS % {"nick": json.dumps(nick, ensure_ascii=False)},
                                               session_id=page.session_id)
        except TimeoutError as e:
            raise ChannelError("window_missing", f"header 探针 CDP 超时（{nick}）: {e}") from None
        try:
            data = json.loads(raw) if isinstance(raw, str) else (raw or {})
        except json.JSONDecodeError:
            return None
        if not bool(data.get("hasNick")):
            self.log.warning("header 未确认昵称「%s」，聊天区 header 文本: %s",
                             nick, data.get("header"))
            return None
        return data

    def _read_lines(self, page, now: datetime) -> list[dict]:
        data = page.read_messages()
        lines = []
        cards_n = 0
        for ln in attach_times(data.get("events") or []):
            if ln.get("kind") == "media":
                line = _media_line(ln, now)
                if line:
                    lines.append(line)
                continue
            if ln.get("kind") == "card":
                cards_n += 1
                item_id = str(ln.get("item_id") or "")
                lines.append({
                    "direction": "in" if ln.get("dir") == "in" else ("in" if ln.get("dir") == "?" else "out"),
                    "text": "",   # 卡片无标题文本（13 号 §2.2）
                    "receiveTime": parse_msg_time(ln.get("time_text") or "", now),
                    "kind": "card",
                    "item_id": item_id,
                    "ext": {
                        "type": "product",
                        "itemId": item_id,
                        "title": "",
                        "price": ln.get("price") or "",
                        "delivery": ln.get("delivery") or "",
                        "imageUrl": ln.get("image_url") or "",
                        "url": ln.get("url") or "",
                    },
                })
                continue
            lines.append({
                "direction": "in" if ln.get("dir") == "in" else ("in" if ln.get("dir") == "?" else "out"),
                "text": (ln.get("text") or "").strip(),
                "receiveTime": parse_msg_time(ln.get("time_text") or "", now),
                "kind": "msg",
                "item_id": "",
                "ext": None,
            })
        if cards_n:
            self.log.debug("会话消息区含 %d 张商品卡（并入事件流上报）", cards_n)
        return lines

    # ------------------------------------------------------------------ 内部：发送判定
    def _prepared_ok(self, prepared: dict, content: str) -> bool:
        """输入框写入验证：PREPARE_JS 回读值归一化包含比对（容忍空白/标点差异）。

        纯标点内容（如「。。。」）归一化后为空，回退原文包含比对，避免验证恒为 False（任务 #11 教训）。
        """
        if not isinstance(prepared, dict) or prepared.get("error"):
            return False
        raw = str(prepared.get("value") or "")
        norm = _normalize(content)
        if not norm:
            return bool(content.strip()) and content.strip() in raw
        return norm in _normalize(raw)

    def _verdict(self, sent: dict, content: str) -> tuple[bool | None, str]:
        """发送结果三态判定（移植 ui_ocr §5.5 步骤 7 语义）：
        True=DOM 读到 out 气泡（或输入框已清空）；None=已点发送但未验证（交 Java 兜底）；False=未发出。
        """
        if not isinstance(sent, dict) or sent.get("error"):
            err = sent.get("error") if isinstance(sent, dict) else "无返回"
            return False, f"未找到「发送」按钮（{err}），可能不在聊天页"
        raw_content = content.strip()
        norm = _normalize(content)
        if not norm and not raw_content:
            return False, "回复内容为空"
        for m in sent.get("messages") or []:
            if m.get("dir") != "out":
                continue
            if not norm:
                # 纯标点内容：归一化比对失效，改原文精确比对气泡文本
                if (m.get("text") or "").strip() == raw_content:
                    return True, "发送成功（聊天区验证到新气泡）"
                continue
            t = _normalize(m.get("text") or "")
            # 双向包含：我方内容 ⊂ 气泡文本（残留前缀），或气泡文本 ⊂ 我方内容（漏读尾字）
            if norm in t or (len(t) >= 6 and t in norm):
                return True, "发送成功（聊天区验证到新气泡）"
        before = sent.get("before")
        after = sent.get("after")
        if before and not after:
            return True, "发送成功（聊天区未匹配到气泡，但输入框已清空）"
        return None, "已点发送但聊天区未验证到新气泡（不重发，交 Java 超时兜底）"

    # ------------------------------------------------------------------ 内部：R5 闸门
    def _check_paused(self) -> None:
        if self.paused_check and self.paused_check():
            raise ChannelError("paused", "人工暂停中，中止通道操作")

    def _is_paused(self) -> bool:
        return bool(self.paused_check and self.paused_check())

    def _interrupt_requested(self) -> bool:
        """P0-2（12 号文档）：中断信号探测（状态机注入「任务队列非空或 wake 已置」）。

        默认 interrupt_check=None → 恒 False（不可中断，ui_ocr 行为零变化）。
        """
        return bool(self.interrupt_check and self.interrupt_check())
