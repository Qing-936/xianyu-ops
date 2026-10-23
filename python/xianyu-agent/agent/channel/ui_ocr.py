"""UIA 优先 + OCR 兜底的闲管家通道实现（README.md §5，已停用仅回退参考）。

- 读侧：UIA 遍历元素树读会话列表/聊天区结构文本（POC 期已验证
  会话列表可读）；UIA 读不到时截图对应区域 OCR 兜底（D-03-7）。
- 发送侧：§5.5 九步流程（切账号 → 开会话 → 2.5 身份校验 → 定位输入框 → 粘贴 →
  验证 → 点发送 → 验证新气泡 → 导航复位），每步操作后重新截图（03 §0.1-5）。
- 登录页特征检测：读屏出现 config `login_page_keywords` 即抛 ChannelError("login_required")
  ——绝不自动操作登录框（03 §0.1-6），状态机转 BLOCKED。

坐标全部来自 config（03 §0.1-7），窗口每次操作前重新定位。
"""
from __future__ import annotations

import hashlib
import logging
import re
import time
from datetime import datetime, timedelta
from pathlib import Path

from agent.ui import win
from agent.ui.ocr import Ocr
from agent.ui.time_parser import parse as parse_time_text
from agent.vision.change import frames_changed as _frames_changed
from .base import Channel, ChannelError, Message, SendResult


def _sha1(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


# 纯时间分隔行（聊天区时间戳，如「08-28 23:28」「昨天 10:30」「刚刚」「3分钟前」「11:17」）
_TIME_ONLY_RE = re.compile(
    r"^(?:(?:\d{4}[-/年])?\d{1,2}[-/月]\d{1,2}日?(?:\s*\d{1,2}:\d{2})?"
    r"|\d{1,2}:\d{2}"
    r"|昨天(?:\s*\d{1,2}:\d{2})?"
    r"|刚刚"
    r"|\d+\s*(?:分钟|小时|天)前)$"
)

# 比对时剔除的空白/标点（OCR 对气泡易断行、漏标点，逐字比对不可靠）
_PUNCT_RE = re.compile(r"[\s，。！？、；：,.!?~～;:|/（）()\[\]【】<>《》·．]")


def _normalize(text: str) -> str:
    """去空白与常见标点，用于气泡/输入框文本的宽松比对。"""
    return _PUNCT_RE.sub("", text or "")


def _hash_norm(text: str) -> str:
    """hash 专用归一化：纯标点内容归一化后为空时回退原文（strip）。

    否则「，，，」与「？？？」都归一为空串 → hash 碰撞，后者被误判重放丢弃、
    会话预览同理互相抵充（与 webim 通道同源问题，2026-09-22「？？？」漏报）。
    """
    return _normalize(text) or (text or "").strip()


def _group_rows(items: list[dict], y_tol: int = 14) -> list[dict]:
    """文本条目按 y 中心分组为「行」：{texts, x0, x1, yc}。

    UIA/OCR 通用：同一视觉行的元素（昵称/摘要/时间）y 中心差 ≤ y_tol 视为同组。
    ⚠️ 键名兼容：UIA 条目文本在 name、OCR 条目文本在 text（两者结构不同）；
    若只取 text，走 UIA 主路径的 _read_chat_lines 会 KeyError: 'text'（实测踩坑）。
    """
    rows: list[dict] = []
    for it in sorted(items, key=lambda t: t["y0"]):
        cy = (it["y0"] + it["y1"]) // 2
        text = it.get("text") or it.get("name") or ""
        if rows and abs(cy - rows[-1]["yc"]) <= y_tol:
            r = rows[-1]
            r["texts"].append(text)
            r["x0"] = min(r["x0"], it["x0"])
            r["x1"] = max(r["x1"], it["x1"])
            r["yc"] = (r["yc"] + cy) // 2
        else:
            rows.append({"texts": [text], "x0": it["x0"], "x1": it["x1"], "yc": cy})
    return rows


def _is_badge(it: dict) -> bool:
    """未读角标特征：纯数字（可带 +）且框小 —— 防与账号名/会话行粘连。

    实测闲管家未读角标紧贴账号名（角标「3」box(145,25)-(158,39) 与
    「咖啡泡茶」box(163,22)-(249,46) y 中心相近），_group_rows 会并入同一行
    拼成「咖啡泡茶3」；会话列表行同理。
    """
    text = (it.get("text") or it.get("name") or "").strip()
    if not re.fullmatch(r"\d{1,3}\+?", text):
        return False
    return (it["x1"] - it["x0"]) <= 40 and (it["y1"] - it["y0"]) <= 40


class UiOcrChannel(Channel):
    """UIA 优先 + OCR 兜底通道（仅主线程使用，03 §1.1 评审 P2-3）。"""

    def __init__(self, cfg: dict, log: logging.Logger, audit, shots_dir: Path):
        self.cfg = cfg
        self.log = log
        self.audit = audit
        self.shots_dir = Path(shots_dir)
        self.shots_dir.mkdir(parents=True, exist_ok=True)
        ui = cfg["ui"]
        self.proc_name = ui["proc_name"]
        self.tab_region = ui["account_tab_region"]
        self.list_region = ui["conversation_list_region"]
        self.chat_region = ui["chat_area_region"]
        self.input_region = ui.get("chat_input_region") or [self.chat_region[0], self.chat_region[3],
                                                            self.chat_region[2], self.chat_region[3] + 300]
        self.header_region = ui["chat_header_region"]
        self.delay = float(ui.get("actions_delay_ms", 300)) / 1000.0
        self.login_keywords = ui.get("login_page_keywords", []) or []
        self.tab_ignore = set(ui.get("account_tab_ignore", []) or [])
        self.tab_ignore.add("")  # 空文本永远不是账号
        # 列表区左侧导航栏右边界（实测导航项 x1 ≤ 87、会话内容 x0 ≥ 104）
        self.nav_rail_right = int(ui.get("conversation_nav_rail_right", 95))
        # 会话块提取参数（实测昵称 x0≈188-191、块内行间距 ≤37、跨块 ≥72）
        self.line_y_tol = int(ui.get("conversation_line_y_tol", 12))
        self.block_gap_px = int(ui.get("conversation_block_gap_px", 60))
        self.nick_x_min = int(ui.get("conversation_nick_x_min", 180))
        self.nick_x_max = int(ui.get("conversation_nick_x_max", 230))
        # 打开会话：UIA 扫描得到的行点击点可能略高于「列表 OCR 裁剪顶边」list_region[1]
        # （顶部会话行昵称常因标定余量落在顶边之外），若拒绝该合法点击点会跌落被裁掉的
        # 列表区 OCR 兜底而误判 buyer_missing。给一个向上容差放行 UIA 明确定位到的首行。
        self.click_y_tol_up = int(ui.get("conversation_click_y_tol_up", 28))
        self.conv_ignore = list(ui.get("conversation_ignore_keywords", []) or [])
        # 聊天区非消息行过滤（商品卡片「¥/含运费/卡号」等，实测紧贴标题下方易误读为买家消息）
        self.chat_ignore = list(ui.get("chat_ignore_keywords", []) or [])
        ocr_cfg = cfg.get("ocr", {})
        self.ocr = Ocr(lang=ocr_cfg.get("lang", "ch"),
                       min_confidence=float(ocr_cfg.get("min_confidence", 0.85)),
                       use_cls=bool(ocr_cfg.get("use_cls", False)),
                       intra_op_num_threads=int(ocr_cfg.get("intra_op_num_threads", 0) or 0),
                       log=log)
        self.retry_shot = int(ocr_cfg.get("retry_shot", 1))
        # 截图默认只在内存（03 §5.1 提速）：ui.debug_shots=true 时全部落盘便于排查
        self.debug_shots = bool(ui.get("debug_shots", False))
        # 每轮 UI 开销观测（stats_reset 每轮调用；聚合日志见 state_machine 的「本轮耗时」）
        self.stats: dict[str, list[float]] = {}
        # UIA 全树遍历代际缓存：一轮内多次读取复用同一次遍历，任何 UI 变更后失效
        self._uia_cache: tuple[int, list[dict]] | None = None
        # 「消息」导航与「发送」按钮坐标缓存（同一窗口尺寸下位置固定），值为 (w, h, x, y)
        self._nav_point: tuple[int, int, int, int] | None = None
        self._send_point: tuple[int, int, int, int] | None = None
        # 长扫描进度心跳（状态机注入 touch_main_loop）：逐个会话回调，避免单轮读会话被误判卡死
        self.on_progress = None
        # R5 人工接管闸门（状态机注入）：返回 True 时所有 UI 动作立即中止（抛 ChannelError:
        # paused），用于人工点悬浮条「暂停」后 ≤1 个动作内停手（不必等整轮扫描跑完）
        self.paused_check = None
        # —— 07 号文档 R1：读取可靠性配置（开关 + 参数，见 config.yaml read/vision 段） ——
        rd = cfg.get("read", {})
        self.read_enable_time_change = bool(rd.get("enable_time_change", True))      # T1.1
        self.read_enable_force_open = bool(rd.get("enable_force_open", True))        # T1.2
        self.read_enable_blacklist = bool(rd.get("enable_blacklist", True))          # T1.4
        self.read_enable_open_diff = bool(rd.get("enable_open_diff", True))          # T1.3
        self.time_tol = timedelta(seconds=int(rd.get("time_tol_sec", 300)))          # 时间变化容差
        self.active_window = timedelta(minutes=int(rd.get("active_window_min", 120)))
        self.force_every = int(rd.get("force_every", 10))
        self.max_list_rows = int(rd.get("max_list_rows", 30))
        self.open_fail_max = int(rd.get("open_fail_max", 3))
        self.blacklist_ttl = timedelta(minutes=int(rd.get("blacklist_ttl_min", 30)))
        vs = cfg.get("vision", {})
        self.diff_noise = int(vs.get("noise_threshold", 25))
        self.diff_ratio = float(vs.get("diff_ratio", 0.003))
        self.diff_blob = int(vs.get("local_blob_min_px", 60))

    # ------------------------------------------------------------------ 观测
    def stats_reset(self) -> None:
        self.stats = {}

    def _stat(self, name: str, sec: float, count: int = 1) -> None:
        s = self.stats.setdefault(name, [0.0, 0.0])
        s[0] += count
        s[1] += sec

    def stats_summary(self) -> str:
        """单行统计：`uia_walk=2/0.31s ocr=1/1.02s click=3/1.98s`（次数/累计耗时）。"""
        if not self.stats:
            return "无 UI 操作"
        return " ".join(f"{k}={int(v[0])}/{v[1]:.2f}s" for k, v in sorted(self.stats.items()))

    # ------------------------------------------------------------------ 人工接管闸门（R5）
    def _is_paused(self) -> bool:
        """R5 人工暂停判定（状态机注入 paused_check；未注入恒 False，测试/独立运行无影响）。"""
        return bool(self.paused_check and self.paused_check())

    def _check_paused(self) -> None:
        """R5 闸门：人工暂停请求生效时抛 ChannelError("paused")，UI 动作立即中止。

        在 _window/_click/_paste_and_verify 三个 UI 入口调用：人工点击悬浮条「暂停」后
        ≤1 个动作即停手（不必等整轮扫描跑完），由状态机捕获后转 PAUSED。
        """
        if self._is_paused():
            raise ChannelError("paused", "人工暂停中，中止 UI 操作")

    # ------------------------------------------------------------------ 通道生命周期钩子（M0）
    def warmup(self) -> None:
        """进程启动预热：OCR 模型预热（空白图，03 §2.1 INIT）。"""
        self.ocr.warmup()

    def health_check(self) -> None:
        """IDLE 巡检（基类契约）：定位 + 前台化即算画面可用，失败抛 ChannelError。"""
        self._window()

    # ------------------------------------------------------------------ 基础工具
    def _window(self) -> int:
        """重新定位闲管家主窗口（03 §0.1-7：每次操作重新定位）。"""
        self._check_paused()  # R5 闸门：人工暂停中不抢窗口/不截图
        hwnd, pid = win.find_window_by_proc_name(self.proc_name)
        if not hwnd:
            raise ChannelError("window_missing", f"未找到进程名含「{self.proc_name}」的窗口")
        if not win.set_foreground(hwnd):
            # 锁屏/其他窗口遮挡时不可截图（mss 按屏幕坐标，会截到无关窗口内容）
            raise ChannelError("window_missing", "窗口无法置前台（可能被锁屏或其他窗口遮挡）")
        self._uia_cache = None  # 前台化可能引起重排，缓存作废
        size = self._window_size(hwnd)
        for attr in ("_nav_point", "_send_point"):
            cached = getattr(self, attr)
            if cached and (cached[0], cached[1]) != size:
                setattr(self, attr, None)  # 窗口尺寸变了，缓存坐标不再适用
        return hwnd

    @staticmethod
    def _window_size(hwnd: int) -> tuple[int, int]:
        left, top, right, bottom = win.rect_of(hwnd)
        return right - left, bottom - top

    def _snap(self, hwnd: int, tag: str, region: list | None = None,
              keep: bool = False) -> list[dict]:
        """截图（区域或全窗）+ OCR → 条目（窗口相对坐标）。

        默认只在内存抓图（提速：正常轮次零磁盘 IO）；keep=True 或 ui.debug_shots
        才写 logs/shots/。OCR 坐标以截图像素为基准，有区域时统一加区域原点偏移，
        换算成「窗口相对坐标」（与 config 区域、click_window_xy 同一坐标系）。M3 实测
        缺该偏移时点击会话行会整体偏移 y=区域原点，导致点到空白、聊天区残留上一个会话内容。
        """
        t0 = time.perf_counter()
        if region:
            img = win.grab_area(hwnd, region[0], region[1], region[2], region[3])
        else:
            img = win.grab_window(hwnd)
        if keep or self.debug_shots:
            ts = datetime.now().strftime("%H%M%S")
            win.save_image(img, self.shots_dir / f"{tag}-{ts}.png")
        items = self.ocr.run(img)
        self._stat("ocr", time.perf_counter() - t0)
        dx, dy = (region[0], region[1]) if region else (0, 0)
        if dx or dy:
            for it in items:
                it["x0"] += dx
                it["x1"] += dx
                it["y0"] += dy
                it["y1"] += dy
        return items

    def _uia_items(self, hwnd: int) -> list[dict]:
        """UIA 条目（代际缓存）：一轮内 read_accounts / 会话列表 / 聊天区共用同一次全树遍历。

        缓存只由 _uia_invalidate 失效（点击、粘贴、前台化等任何 UI 变更后），
        因此「用缓存坐标点击」与「重新遍历」的语义等价于今天的逐步重拍。
        """
        cache = self._uia_cache
        if cache is None or cache[0] != hwnd:
            items = self._uia_walk(hwnd)
            self._uia_cache = (hwnd, items)
            return items
        return cache[1]

    def _uia_invalidate(self) -> None:
        self._uia_cache = None

    def _click(self, hwnd: int, x: int, y: int) -> None:
        """点击（统一入口：失效 UIA 缓存 + 统计耗时）。"""
        self._check_paused()  # R5 闸门：人工暂停中不动鼠标
        t0 = time.perf_counter()
        self._uia_invalidate()
        win.click_window_xy(hwnd, x, y)
        self._stat("click", time.perf_counter() - t0)

    def _wait_until(self, fn, cap_sec: float, interval: float = 0.25) -> bool:
        """轮询到条件成立即继续（替代盲睡）；cap_sec 沿用原 `delay*N` 作为**上限**。

        判定式与今天完全一致（由调用方传入），只是把「先睡满再判一次」变成
        「判到即走、睡不满不追」——严格度不降，省下未被使用的固定等待。
        """
        deadline = time.perf_counter() + cap_sec
        while True:
            if fn():
                return True
            remain = deadline - time.perf_counter()
            if remain <= 0:
                return False
            time.sleep(min(interval, remain))

    def _uia_walk(self, hwnd: int) -> list[dict]:
        """UIA 遍历窗口元素树（POC select_and_read.walk 演进），
        返回 [{name, x0, y0, x1, y1, el}]（已转窗口相对坐标）；读不到抛 ChannelError。

        pywinauto 的 rectangle() 是屏幕绝对坐标，须减窗口原点，才能与 config 区域
        及 _snap 的 OCR 坐标在同一坐标系（M3 实测混用会错判分区）。
        """
        try:
            from pywinauto import Desktop

            t0 = time.perf_counter()
            left, top, _, _ = win.rect_of(hwnd)
            win_obj = Desktop(backend="uia").window(handle=hwnd)
            items: list[dict] = []

            def walk(e, depth=0):
                try:
                    name = e.element_info.name
                    rect = e.rectangle()
                except Exception:
                    return
                if name and name.strip():
                    items.append({
                        "name": name.strip(),
                        "x0": int(rect.left) - left, "y0": int(rect.top) - top,
                        "x1": int(rect.right) - left, "y1": int(rect.bottom) - top,
                        "el": e,
                    })
                try:
                    for c in e.children():
                        walk(c, depth + 1)
                except Exception:
                    pass

            walk(win_obj)
            self._stat("uia_walk", time.perf_counter() - t0)
            return items
        except Exception as e:
            raise ChannelError("uia_unavailable", str(e)) from e

    def _detect_login_page(self, texts: list[str]) -> bool:
        """登录页特征：出现 ≥2 个特征词即判定（03 §6 异常 1，避免误伤正常聊天）。"""
        hits = sum(1 for t in texts if any(kw in t for kw in self.login_keywords))
        return hits >= 2

    def _conv_blacklisted(self, cursor, nick: str, now: datetime) -> bool:
        """T1.4（07 号文档）：会话是否处于熔断黑名单期（开关关闭/未熔断/已到期 → False）。"""
        if not self.read_enable_blacklist:
            return False
        c = cursor.convs.get(nick)
        return bool(c and c.blacklisted_until and now < c.blacklisted_until)

    def _open_diff(self, before, after) -> bool | None:
        """T1.3（07 号文档）：打开会话前后帧 diff（开关关闭/帧缺失 → None，fail-open）。"""
        if not self.read_enable_open_diff:
            return None
        try:
            return _frames_changed(before, after, self.diff_noise, self.diff_ratio, self.diff_blob)
        except Exception:
            return None  # diff 是辅助证据，异常一律 fail-open，不影响主流程

    def _grab_region_safe(self, hwnd: int, region: list):
        """T1.3 抓帧（fail-open）：mss 遮挡/黑屏等异常返回 None，不中断读取主流程。"""
        try:
            return win.grab_area(hwnd, *region)
        except Exception:
            return None

    # ------------------------------------------------------------------ 账号快照
    def read_accounts(self) -> list[dict]:
        """§5.2：账号 tab 区读已登录账号昵称（UIA 优先，OCR 兜底）。

        授权 ID 若界面可见则读（M3 实测校准点：当前版本读不到 → 只报昵称，
        Java 退昵称匹配，01 §3.6 已接受该运维成本）。tab 存在 = 已登录 online。
        """
        hwnd = self._window()
        # UIA：tab 区域内元素按 x 排序取文本；过滤状态标签/添加按钮等噪声（account_tab_ignore）
        names: list[str] = []
        try:
            items = self._uia_items(hwnd)
            x0, y0, x1, y1 = self.tab_region
            for it in items:
                if x0 <= it["x0"] and it["x1"] <= x1 and y0 <= it["y0"] and it["y1"] <= y1:
                    name = (it["name"] or "").strip()
                    if not name or name in self.tab_ignore or len(name) <= 1:
                        continue  # 噪声：状态标签（在线/离线）、添加按钮（+）、单字符
                    if _is_badge(it):
                        continue  # 未读角标（纯数字小框）
                    if name not in names:
                        names.append(name)
        except ChannelError:
            names = []
        if not names:
            items = self._snap(hwnd, "acct-tab", self.tab_region)
            items = [it for it in self.ocr.high_conf(items) if not _is_badge(it)]
            rows = _group_rows(items, y_tol=8)
            names = ["".join(r["texts"]).strip() for r in sorted(rows, key=lambda r: r["x0"])]
            names = [n for n in names if n not in self.tab_ignore and len(n) > 1]
        accounts = [
            {"userNick": n, "authorizeId": None, "loginStatus": "online"}
            for n in names if n
        ]
        self.log.info("账号快照: %s", [a["userNick"] for a in accounts])
        return accounts

    # ------------------------------------------------------------------ 会话列表
    def _scan_conversations(self, hwnd: int) -> list[dict]:
        """扫描会话列表 → [{buyerNick, summary, summaryHash, unread, click}]（按 y 排序）。

        实测结构（消息页列表区 OCR）：每个会话 = 昵称行（可含未读角标 + 昵称 x0≈189）
        + 预览行（标签/消息内容/时间）。UIA 主路径可用时优先，读不到走 OCR 兜底；
        两类条目统一交给 _extract_conversations 按会话块聚类提取。
        """
        x0, y0, x1, y1 = self.list_region
        zone: list[dict] = []
        try:
            items = self._uia_items(hwnd)
            zone = [it for it in items if it["x1"] <= x1 + 20 and it["y1"] >= y0 - 4
                    and it["x1"] > self.nav_rail_right]
        except ChannelError:
            zone = []
        if not zone:
            items = self._snap(hwnd, "conv-list", self.list_region)
            zone = [it for it in self.ocr.high_conf(items) if it["x1"] > self.nav_rail_right]
        rows = self._extract_conversations(zone)
        for r in rows:
            # 摘要 hash 用 _normalize 去标点/空白：OCR 对同一摘要的标点抖动
            # （如「321」vs「..321」）会导致 hash 每轮变化、会话被每轮重开
            r["summaryHash"] = _sha1(r["buyerNick"] + "|" + _hash_norm(r["summary"]))
            # 07 号文档 T1.1：会话行相对时间解析为估计时刻（time_parser 支持
            # 刚刚/x分钟前/x小时前/x天前/昨天/HH:mm/日期），供时间变化门控与
            # T1.2 活跃会话判定；解析失败为 None（调用方退回归一化文本比较）
            r["time_est"] = parse_time_text(r.get("time_text") or "")
        return rows

    def _extract_conversations(self, items: list[dict]) -> list[dict]:
        """列表区文本条目 → 会话块提取 [{buyerNick, summary, unread}]。

        两步聚类：① 视觉行（同行多文本，y 中心差 ≤ line_y_tol）；② 会话块
        （相邻行 y 差 ≤ block_gap_px；实测块内 ≤37、跨块 ≥72）。
        条目级过滤：非角标且 x0 < nick_x_min 的文本（列表表头/筛选 tab/搜索框等
        控件噪声）与命中 conversation_ignore_keywords 的文本（空态/通知消息等）
        不进入聚类；ignore 命中因此会丢弃整个会话块。
        昵称 = 块内首个 x0 ∈ [nick_x_min, nick_x_max] 的非角标非噪声文本
        （实测昵称列对齐 x0≈188-191，左侧导航 x1≤87、订单筛选 x0=106/176/268 均排除）。
        unread = 块内出现未读角标（纯数字小框，实测角标在昵称左侧 x≈161）。
        click = 昵称条目矩形中心（打开该会话的直接点击点，省一次区域 OCR）。
        """
        entries: list[tuple[str, int, int, bool, int]] = []
        for it in items:
            text = (it.get("text") or it.get("name") or "").strip()
            if not text:
                continue
            badge = _is_badge(it)
            if not badge:
                # 昵称列左侧文本 = 列表控件噪声（表头「消息/清除未读/设置提示音」、
                # 筛选 tab、搜索框）；ignore 关键词 = 非会话行（空态/通知消息等），
                # 在条目级过滤：否则 UIA 幻象条目会并入相邻会话块污染 summary
                if it["x0"] < self.nick_x_min:
                    continue
                if any(kw in text for kw in self.conv_ignore):
                    continue
            cy = (it["y0"] + it["y1"]) // 2
            entries.append((text, it["x0"], cy, badge, (it["x0"] + it["x1"]) // 2))
        if not entries:
            return []
        entries.sort(key=lambda e: e[2])
        # ① 视觉行
        lines: list[dict] = []
        for text, ex0, cy, badge, ecx in entries:
            if lines and abs(cy - lines[-1]["cy"]) <= self.line_y_tol:
                lines[-1]["items"].append((text, ex0, badge, ecx))
            else:
                lines.append({"items": [(text, ex0, badge, ecx)], "cy": cy})
        # ② 会话块
        blocks: list[dict] = []
        for line in lines:
            if blocks and line["cy"] - blocks[-1]["last_cy"] <= self.block_gap_px:
                blocks[-1]["lines"].append(line)
                blocks[-1]["last_cy"] = line["cy"]
            else:
                blocks.append({"lines": [line], "last_cy": line["cy"]})
        rows: list[dict] = []
        for b in blocks:
            badges = [text for line in b["lines"] for (text, _, badge, _) in line["items"] if badge]
            unread = bool(badges)
            unread_count = max((int(t.rstrip("+")) for t in badges if t.rstrip("+").isdigit()), default=0)
            nick = None
            click: tuple[int, int] | None = None
            for line in b["lines"]:
                for (text, ex0, badge, ecx) in line["items"]:
                    if badge or not (self.nick_x_min <= ex0 <= self.nick_x_max):
                        continue
                    nick = text
                    click = (ecx, line["cy"])
                    break
                if nick:
                    break
            if not nick:
                continue  # 无昵称块（仅角标/商品图等噪声）
            if _TIME_ONLY_RE.match(nick):
                continue  # 昵称被 ignore 过滤后只剩时间行的退化块（如通知消息）
            summary = "".join(text for line in b["lines"] for (text, _, badge, _) in line["items"]
                              if not badge and text != nick and not _TIME_ONLY_RE.match(text))
            # 摘要排除时间文本（例「48分钟前」「08-28」）——时间每轮变化会导致 summaryHash 抖动
            # （07 号文档 T1.1：时间文本单独保留为 time_text，作为「有新消息」的变化信号）
            time_text = next((text for line in b["lines"] for (text, _, badge, _) in line["items"]
                              if not badge and text != nick and _TIME_ONLY_RE.match(text)), None)
            rows.append({"buyerNick": nick, "summary": summary, "unread": unread,
                         "unread_count": unread_count, "click": click, "time_text": time_text})
        return rows

    def _goto_conversation_list(self, hwnd: int, force: bool = False) -> None:
        """导航到会话列表视图（POC open_conversation 步骤 1）。

        导航项位置在固定窗口尺寸下不变 → 首次 OCR 定位后缓存坐标复用（今天每次导航
        都做一遍全窗 OCR，且每读一个会话就重复一次，是读侧最大单项开销）。force 由
        上层在「导航后列表读不出行」时传入，强制重新 OCR 定位（缓存点失效自愈）。
        """
        w, h = self._window_size(hwnd)
        cached = self._nav_point
        if cached and not force and (cached[0], cached[1]) == (w, h):
            self._click(hwnd, cached[2], cached[3])
            # 视图切换需渲染：等到列表区真出现会话行为止，上限沿用原 delay*4
            self._wait_until(lambda: self._list_ready(hwnd), self.delay * 4, interval=self.delay)
            return
        items = self._snap(hwnd, "nav")
        nav = self.ocr.find_text(items, "消息", exact=True)
        if not nav:
            raise ChannelError("nav_missing", "未找到「消息」导航入口")
        rail = [n for n in nav if n["x0"] < 100]   # 左侧导航栏优先
        cx, cy = self.ocr.center((rail or nav)[0])
        self._click(hwnd, cx, cy)
        self._nav_point = (w, h, cx, cy)
        time.sleep(self.delay * 4)

    def _list_ready(self, hwnd: int) -> bool:
        """列表区是否已有会话行（导航后视图就绪判据；强制重遍历，结果留给缓存复用）。"""
        self._uia_invalidate()
        try:
            items = self._uia_items(hwnd)
        except ChannelError:
            return False  # UIA 不可用时交给上层 OCR 兜底（不在此触发 OCR）
        _, y_top, x1, _ = self.list_region
        return any(it["x0"] >= self.nick_x_min and it["x1"] <= x1 + 20 and it["y1"] >= y_top - 4
                   for it in items)

    def _header_shows(self, hwnd: int, nick: str) -> bool:
        """会话页顶部标题栏是否显示该买家（发送前身份闸门的判定式，区域 OCR）。"""
        items = self._snap(hwnd, "chat-header", self.header_region)
        return bool(self.ocr.find_text(self.ocr.high_conf(items), nick))

    def _click_account_tab(self, hwnd: int, user_nick: str) -> None:
        """步骤 1：账号 tab 区 OCR 找昵称并点击切换；找不到抛 account_missing。

        保持 OCR 定位（tab 点错会把别的账号会话记到本账号名下，像素判据比 UIA 幻象条目可靠）。
        """
        items = self._snap(hwnd, "acct-tab", self.tab_region)
        cands = self.ocr.find_text(self.ocr.high_conf(items), user_nick)
        if not cands:
            raise ChannelError("account_missing", f"账号 tab 找不到: {user_nick}")
        self._click(hwnd, *self.ocr.center(cands[0]))
        time.sleep(self.delay * 4)

    def _click_buyer_row(self, hwnd: int, buyer_nick: str,
                         point: tuple[int, int] | None = None) -> None:
        """步骤 2：打开买家会话——优先用扫描已知的行坐标点击，OCR 定位作兜底。

        点击后等顶部出现买家昵称（上限＝原 delay*6 盲睡），判定式与发送闸门一致：
        既省掉固定等待，又堵住「点到空白/错行 → 把别人的会话内容记到该买家名下」。
        """
        x0, y0, x1, y1 = self.list_region
        if point and x0 <= point[0] <= x1 and (y0 - self.click_y_tol_up) <= point[1] <= y1 + 60:
            self._click(hwnd, *point)
            if self._wait_until(lambda: self._header_shows(hwnd, buyer_nick),
                                self.delay * 6, interval=self.delay):
                return
            self._nav_point = None  # 缓存坐标没打开目标会话，导航/坐标缓存一律作废
        items = self._snap(hwnd, "conv-list", self.list_region)
        cands = self.ocr.find_text(self.ocr.high_conf(items), buyer_nick)
        if not cands:
            raise ChannelError("buyer_missing", f"会话列表找不到买家: {buyer_nick}")
        cands.sort(key=lambda c: (c["y0"], c["x0"]))
        self._click(hwnd, *self.ocr.center(cands[0]))
        if not self._wait_until(lambda: self._header_shows(hwnd, buyer_nick),
                                self.delay * 6, interval=self.delay):
            raise ChannelError("conv_not_open", f"点击后顶部未出现买家「{buyer_nick}」，未读取该会话")

    # ------------------------------------------------------------------ 聊天区读取
    def _read_chat_lines(self, hwnd: int, buyer_nick: str) -> list[dict]:
        """读当前聊天区消息行 → [{text, direction, receiveTime(datetime|None), x0, yc}]。

        UIA 主路径（POC select_and_read：找「聊天_闲鱼」root 的 descendants）；
        OCR 兜底截图 chat_area_region。非消息行（商品卡片等 chat_ignore_keywords）不产出。
        方向按**气泡右边缘**判：右边缘越中线 = 我方（out）。用起点判会误伤长气泡——
        实测我方长消息左边缘会跨过中线（起点 970 < 中线 1022），被当成买家消息入库。
        时间：聊天区气泡内文本不带时间戳，时间戳是独立分隔行（例 13:39 / 08-28 23:28），
        该行不产出消息，但作为**时间锚点**向下传播；丢弃锚点会导致消息 receiveTime 全空。
        """
        lines: list[dict] = []
        cx0, cy0, cx1, cy1 = self.chat_region
        mid_x = (cx0 + cx1) // 2
        try:
            items = self._uia_items(hwnd)
            zone = [it for it in items if cx0 <= it["x0"] and it["x1"] <= cx1 + 2
                    and cy0 <= it["y0"] and it["y1"] <= cy1 + 2]
        except ChannelError:
            zone = []
        if not zone:
            items = self._snap(hwnd, "chat-area", self.chat_region)
            zone = self.ocr.high_conf(items)
        anchor: datetime | None = None
        for r in _group_rows(zone, y_tol=14):
            text = "".join(r["texts"]).strip()
            if not text:
                continue
            if any(kw in text for kw in self.chat_ignore):
                continue  # 商品卡片等非消息行（例：¥197.00~493.50 / 含运费0.00元）
            if _TIME_ONLY_RE.match(text):
                t = parse_time_text(text)
                if t is not None:
                    anchor = t  # 时间锚点：作用于其下方的消息（时间戳显示在消息上方）
                continue
            if not _normalize(text):
                continue  # 纯标点/空白行（OCR 分片残字，如「，」）：不是有效消息，不产出
            lines.append({"text": text, "direction": "out" if r["x1"] >= mid_x else "in",
                          "x0": r["x0"], "yc": r["yc"],
                          "receiveTime": parse_time_text(text) or anchor})
        return lines

    # ------------------------------------------------------------------ 读未读
    def read_unread(self, account: dict, cursor) -> list[Message]:
        """§2.1 SCAN/READ：扫描该账号会话列表，打开 summary 变化的会话读新消息。

        返回游标过滤后的 in 消息（receiveTime 为校正前本地时间，状态机统一加时钟偏移）。
        进程首轮（cursor.initialized=False）只建基线不读聊天区（防历史消息全量上报）；
        运行中新增会话读并上报近 10 分钟消息（首条触发消息不丢）。
        会话「本进程首次读取」时只认晚于 cursor.started_at 的消息（防重启后摘要抖动
        触发的整会话重读把历史当新消息重放）。
        门控（§5.6 + 07 号文档 R1）：非首轮仅当摘要变 / 未读数增 / 未读角标且未排空 /
        **时间文本前进**（T1.1）才打开；另每 force_every 轮强制打开 1 个活跃会话
        兜底对账（T1.2，不依赖任何 UI 信号）；打开失败连续计数熔断（T1.4），
        打开前后帧 diff 记录辅助证据（T1.3）。
        """
        user_nick = account["userNick"]
        hwnd = self._window()
        try:
            self._click_account_tab(hwnd, user_nick)
        except ChannelError:
            pass  # 单账号时 tab 可能不存在，继续尝试读列表
        self._goto_conversation_list(hwnd)
        rows = self._scan_conversations(hwnd)
        if not rows:
            # 缓存导航点可能已失效（点了却没落到列表视图）：OCR 重新定位一次再扫
            self._nav_point = None
            self._goto_conversation_list(hwnd)
            rows = self._scan_conversations(hwnd)
        if self._detect_login_page([r["buyerNick"] + r["summary"] for r in rows]):
            raise ChannelError("login_required", "会话列表出现登录页特征")

        # 进程首轮：建基线（03 §5.4 游标初始化）；例外：带未读角标的会话要读，
        # 否则启动时刻已存在的未读消息会被基线吞掉（实测：手机消息先于 Agent 启动）
        now = datetime.now()
        first_round = not cursor.initialized
        readable: list[dict] = []
        if first_round:
            for row in rows:
                c = cursor.conv(row["buyerNick"])
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
            self.log.info("账号 %s 首轮扫描：%d 个会话已建基线，其中 %d 个有未读（读取上报）",
                          user_nick, len(rows), len(readable))
        else:
            for row in rows:
                c = cursor.conv(row["buyerNick"])
                # 变化检测用「该昵称已见摘要 hash 集合」而非单一 last_summary_hash：
                # 同一昵称可有多条会话线程（如买家分商品会话），单值会在两线程间
                # 每轮互相覆盖、导致两行都被误判「摘要已变」而每轮重开。
                changed = row["summaryHash"] not in c.summary_hashes
                more = row.get("unread_count", 0) > (c.last_unread_count or 0)
                row["_summary_changed"] = changed  # 本轮读取是否由「摘要变化」触发（供读取过滤判断）
                # T1.1：时间变化检测——会话行相对时间前进 = 有新消息的强信号
                # （诊断结论：无角标 + 摘要恒定时门控三条件全假，时间信号是唯一补救）
                time_new = False
                if self.read_enable_time_change:
                    t_est = row.get("time_est")
                    if t_est is not None:
                        time_new = c.last_time_est is None or t_est > c.last_time_est + self.time_tol
                    else:
                        tn = _normalize(row.get("time_text") or "")
                        time_new = bool(tn) and tn != (c.last_time_text_norm or "")
                row["_time_changed"] = time_new
                # 门控：摘要未变且未读数未增且（无角标或上次已排空）且时间未前进 → 本轮不打开
                # （未读角标常驻/红点不消的会话，读完一次后不再每轮重开，省掉最大单项开销）
                if changed or more or (row.get("unread") and not c.drained) or time_new:
                    readable.append(row)

        # T1.2：活跃会话周期兜底——门控全假（无角标/摘要恒定/基线已建）时会话可能
        # 被永久跳过（「玉面小龙女」漏读根因）；每 force_every 轮强制打开 1 个活跃
        # 会话对账，走现有读取管线（content_hash 去重 + §5.8 安全边界全保留）。
        if not first_round:
            cursor.scan_rounds += 1
            if self.read_enable_force_open and len(rows) <= self.max_list_rows \
                    and cursor.scan_rounds % self.force_every == 0:
                readable_nicks = {r["buyerNick"] for r in readable}
                # T1.2 对账兜底候选不再排除黑名单会话：否则「被 T1.4 熔断」+「T1.2 又不重开」
                # 叠加成死锁，误熔断的会话在 TTL 到期前无任何重试机会（红角标挂数小时）。
                # 是否真正跳过由下方 readable 循环判（forced_open 可穿透黑名单）。
                cands = [r for r in rows
                         if r.get("time_est") is not None
                         and now - r["time_est"] <= self.active_window
                         and r["buyerNick"] not in readable_nicks]
                if cands:
                    forced = min(cands, key=lambda r: cursor.conv(r["buyerNick"]).last_forced_open
                                 or datetime.min)
                    forced["_forced"] = True
                    readable.append(forced)
                    self.log.info("账号 %s 兜底读取触发：强制打开活跃会话 %s（角标/摘要均无变化）",
                                  user_nick, forced["buyerNick"])

        messages: list[Message] = []
        for row in readable:
            buyer = row["buyerNick"]
            if self.on_progress:
                self.on_progress()  # 长列表逐个会话报进度（guard 判定「有进度即未卡死」）
            c = cursor.conv(buyer)
            forced_open = bool(row.get("_forced"))
            # T1.4：熔断黑名单中的会话跳过（TTL 到期自动出狱再试）；
            # 但 T1.2 的周期对账（forced_open）放行穿透——否则误熔断会话在 TTL 到期前
            # 无人重开（与候选排除叠加成死锁），每 force_every 轮放行一次对账重开。
            if (self.read_enable_blacklist and c.blacklisted_until
                    and now < c.blacklisted_until and not forced_open):
                continue
            is_new_conv = c.last_summary_hash is None
            # 摘要变化（或新会话）→ 打开会话读聊天区；T1.3 抓前后帧记录 diff 辅助证据
            before = None
            try:
                if self.read_enable_open_diff:
                    before = self._grab_region_safe(hwnd, self.chat_region)
                self._click_buyer_row(hwnd, buyer, row.get("click"))
                after = self._grab_region_safe(hwnd, self.chat_region) if self.read_enable_open_diff else None
                verdict = self._open_diff(before, after)
                c.open_fail_streak = 0
                c.blacklist_strikes = 0  # 成功打开 → 重置累计
                c.blacklisted_until = None  # 对账重开成功 → 立即出狱，恢复正常门控
                if verdict is not None:
                    self.log.info("打开会话 %s diff: %s", buyer, verdict)
            except ChannelError as e:
                # T1.4：打开失败计数（ChannelError 为主证据，diff 为辅助观测）；
                # 连续 open_fail_max 次熔断，不再每轮重试（伪会话白耗 5s/轮）
                after = None
                if before is not None:
                    after = self._grab_region_safe(hwnd, self.chat_region)
                verdict = self._open_diff(before, after)
                c.open_fail_streak += 1
                if self.read_enable_blacklist and c.open_fail_streak >= self.open_fail_max:
                    c.blacklist_strikes += 1
                    ttl = min(self.blacklist_ttl * (2 ** (c.blacklist_strikes - 1)),
                              timedelta(hours=1))  # 重复入狱 TTL 上限收敛为 1h（原 4h 会让红角标挂太久）
                    c.blacklisted_until = now + ttl
                    self.audit.event("conv_blacklisted", account=user_nick, buyer=buyer,
                                     streak=c.open_fail_streak, ttl_min=int(ttl.total_seconds() // 60),
                                     verdict=verdict)
                    self.log.warning("会话 %s 连续 %d 次打开失败（diff=%s），熔断 %d 分钟",
                                     buyer, c.open_fail_streak, verdict,
                                     int(ttl.total_seconds() // 60))
                else:
                    self.log.warning("打开会话失败（下轮重试，游标不推进）: %s（%s）", buyer, e)
                continue
            lines = self._read_chat_lines(hwnd, buyer)
            fresh: list[Message] = []
            first_read = not c.baselined      # 本进程首次读该会话 → 走历史防重放规则
            has_unread = bool(row.get("unread"))   # 会话行未读角标：判断「无法定时消息」是否可信
            summary_changed = bool(row.get("_summary_changed"))  # 本轮由摘要变化触发 → 新内容可信
            in_idx = [i for i, ln in enumerate(lines) if ln["direction"] == "in"]
            last_in_idx = in_idx[-1] if in_idx else -1
            for idx, line in enumerate(lines):
                if line["direction"] != "in":
                    continue
                content = line["text"]
                # 内容 hash 用归一化文本（去空白/标点）：OCR 对同一气泡的分片抖动
                # （如「闲鱼站点Logo」vs「闲鱼站点Logo，」）会导致原始内容 hash 每轮变化、
                # 进程内判重失效；归一化后同一条消息 hash 恒定；纯标点回退原文防撞（_hash_norm）
                h = _sha1(user_nick + "|" + buyer + "|" + _hash_norm(content))
                t = line.get("receiveTime")
                is_last_in = idx == last_in_idx
                if first_round:
                    # 首轮未读会话：即启动时刻已存在的未读消息，时间必早于 started_at，
                    # 故不做时间过滤，由下方 fresh[-1:] 截取最后一条（防历史全量）
                    pass
                elif first_read:
                    # 本进程首次读该会话：读到的内容一律登记 hash（防后续重放），只上报
                    # 「能定时且晚于本进程启动时刻」的消息；无法定时（聊天区顶部、其上方
                    # 本区域无时间锚点）视为历史丢弃——重启后摘要抖动重读的整段历史由此拦住
                    c.content_hashes.add(h)
                    if t is None or (cursor.started_at is not None and t <= cursor.started_at):
                        continue
                else:
                    # 规则 2：时间不晚于游标且 hash 已见 → 丢弃（重读/旧消息）
                    if (t is not None and h in c.content_hashes
                            and c.last_receive_time is not None and t <= c.last_receive_time):
                        continue
                    if t is None:
                        # 无法定时消息：hash 已见 → 必为重读/旧消息，丢弃
                        # （防我方回复改摘要触发重读时，把已上报的无法定时消息二次上报）
                        if h in c.content_hashes:
                            continue
                        # 无法定时且无未读角标 → 摘要抖动/我方消息触发的重读，不可信，不上报。
                        # 例外：本轮由「摘要变化」触发且该消息是聊天区最后一条买家消息
                        # （说明买家刚发、我方未在其后回复）→ 可信，上报
                        if not has_unread and not (summary_changed and is_last_in):
                            continue
                # 运行中新增会话：只报近 10 分钟消息（防历史全量，首条触发消息必在窗口内）；
                # 例外：无法定时但已判「摘要变化 + 最后一条买家消息」可信的，放行
                if is_new_conv and (t is None or t < now - timedelta(minutes=10)) \
                        and not (t is None and summary_changed and is_last_in):
                    continue
                fresh.append(Message(
                    client_msg_id="m-" + h,
                    account_key={"userNick": user_nick, "authorizeId": account.get("authorizeId")},
                    buyer_id=None,
                    buyer_nick=buyer,
                    content=content,
                    content_type="text",
                    receive_time=(t or now).isoformat(timespec="seconds"),
                    raw={"src": "uia/ocr", "x0": line["x0"]},
                ))
            if first_round:
                fresh = fresh[-1:]  # 首轮未读会话只报最后一条买家消息（防历史全量刷屏）
            # 可观测：记录本会话是否真的被打开读取，以及读到的行数/过滤结果
            # （无此行时「未触发读取」与「触发但全被过滤」日志上无法区分）
            self.log.info("读取会话 %s：聊天区 %d 行（in %d），本轮回报 %d 条",
                          buyer, len(lines),
                          sum(1 for ln in lines if ln["direction"] == "in"), len(fresh))
            if fresh:
                for m in fresh:
                    c.content_hashes.add(_sha1(user_nick + "|" + buyer + "|" + _hash_norm(m.content)))
                    t = datetime.fromisoformat(m.receive_time)
                    if c.last_receive_time is None or t > c.last_receive_time:
                        c.last_receive_time = t
                messages.extend(fresh)
                self.audit.event("read", account=user_nick, buyer=buyer, unread=len(fresh),
                                 trigger="forced" if forced_open else "gate")
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
            self._goto_conversation_list(hwnd)
        # T1.4：会话已从列表消失 → 清除熔断状态（下次出现重新尝试）
        if self.read_enable_blacklist:
            present = {r["buyerNick"] for r in rows}
            for nick, cc in cursor.convs.items():
                if nick not in present and (cc.open_fail_streak or cc.blacklisted_until):
                    cc.open_fail_streak = 0
                    cc.blacklisted_until = None
                    cc.blacklist_strikes = 0
        self.log.info("账号 %s 扫描完成：%d 个会话，%d 条新消息",
                      user_nick, len(rows), len(messages))
        return messages

    # ------------------------------------------------------------------ 发送（§5.5）
    def _find_send_button(self, hwnd: int) -> tuple[int, int] | None:
        """「发送」按钮坐标：尺寸键缓存优先，未命中做一次全窗 OCR（今天唯一路径）后缓存。

        不用 UIA 名字匹配：该按钮在 Electron 树里是否暴露「发送」未证实，点错即误触其他控件；
        而全窗 OCR 每次发送做两遍（步骤 4 存在性 + 步骤 6 取坐标）是发送侧最大单项开销。
        """
        w, h = self._window_size(hwnd)
        cached = self._send_point
        if cached and (cached[0], cached[1]) == (w, h):
            return cached[2], cached[3]
        items = self._snap(hwnd, "send-btn")
        btns = self.ocr.find_text(items, self.cfg["ui"].get("send_button_text", "发送"), exact=True)
        if not btns:
            return None
        point = self.ocr.center(btns[0])
        self._send_point = (w, h, point[0], point[1])
        return point

    def _keep_shot(self, hwnd: int, tag: str, region: list | None = None) -> None:
        """失败/不确定时留档一张（正常轮次不落盘，03 §5.1）。"""
        try:
            self._snap(hwnd, tag, region, keep=True)
        except Exception:
            self.log.debug("失败留档截图失败: %s", tag)

    def send_message(self, account: dict, buyer_nick: str, content: str,
                     item_id: str | None = None,
                     known_items: list[str] | None = None,
                     content_type: str = "text",
                     media: dict | None = None) -> SendResult:
        """§5.5 九步发送流程；判定式与等待上限不变（03 §3.5），UI 坐标尽量复用。

        item_id（13 号文档 §五）：商品卡定位弱校验入参，仅 webim 通道消费，本通道忽略。
        known_items（13 号 §五 增强 B）：会话已知商品集合，仅 webim 消费，本通道忽略。
        content_type/media（15 号文档）：本通道仅支持 text；媒体外发由 webim 承担。
        """
        if content_type != "text":
            return SendResult(False, f"ui_ocr 不支持媒体外发: {content_type}")
        user_nick = account["userNick"]
        try:
            hwnd = self._window()
            self._click_account_tab(hwnd, user_nick)
            self._goto_conversation_list(hwnd)
            # 2. 打开买家会话
            self._click_buyer_row(hwnd, buyer_nick)
            # 2.5 会话身份校验（评审 P1 强制闸门）：会话页顶部出现 buyerNick 才继续
            if not self._wait_until(lambda: self._header_shows(hwnd, buyer_nick),
                                    self.delay * 6, interval=self.delay):
                self._keep_shot(hwnd, "send-fail-gate", self.header_region)
                self._nav_point = self._send_point = None
                return SendResult(False, f"会话身份校验失败：顶部未见买家「{buyer_nick}」，已中止不发送")
            # 3/4. 定位输入框：点击输入框区域中心（实测点发送按钮行高度会落在粘贴区外，不聚焦）
            if not self._find_send_button(hwnd):
                self._keep_shot(hwnd, "send-fail-nobtn")
                return SendResult(False, "未找到「发送」按钮，可能不在聊天页")
            ix = (self.input_region[0] + self.input_region[2]) // 2
            iy = (self.input_region[1] + self.input_region[3]) // 2
            self._click(hwnd, ix, iy)
            time.sleep(self.delay)
            # 5. 粘贴 + 验证输入框出现 content（失败重试粘贴 1 次）
            ok = self._paste_and_verify(hwnd, content)
            if not ok:
                ok = self._paste_and_verify(hwnd, content)
            if not ok:
                self._keep_shot(hwnd, "send-fail-paste", self.input_region)
                return SendResult(False, "粘贴验证失败：输入框未见回复内容，已中止不发送")
            # 6. 点发送
            btn = self._find_send_button(hwnd)
            if not btn:
                self._send_point = None
                self._keep_shot(hwnd, "send-fail-nobtn2")
                return SendResult(False, "发送前未找到「发送」按钮，已中止不发送")
            self._click(hwnd, *btn)
            # 7. 验证聊天区出现新气泡（content）：按行聚合 + 归一化，右侧（我方）气泡。
            #    气泡渲染有延迟 → 轮询到出现为止（上限 delay*10，判定式与原 3 次重拍一致）
            sent = self._wait_until(lambda: self._chat_has_sent_bubble(hwnd, content),
                                    self.delay * 10, interval=self.delay)
            detail = "发送成功（聊天区验证到新气泡）"
            if not sent:
                # 兜底：发送后 App 会清空输入框；输出区已不含 content 亦视为已发出
                items = self._snap(hwnd, "after-send-input", self.input_region)
                if not self._row_has_text(items, content):
                    sent = True
                    detail = "发送成功（聊天区未匹配到气泡，但输入框已清空）"
            # 8. 验证截图留档
            verify_path = self.shots_dir.parent / "verify" / f"task-{datetime.now():%H%M%S}.png"
            if sent:
                win.capture_region(hwnd, *self.chat_region, verify_path)
                return SendResult(True, detail, verify_image_path=str(verify_path))
            self._send_point = None  # 未能确认发出，下轮重新按像素定位按钮
            self._keep_shot(hwnd, "send-fail-verify", self.chat_region)
            return SendResult(None, "已点发送但聊天区未验证到新气泡（不重发，交 Java 超时兜底）")
        except ChannelError as e:
            self._send_point = None
            return SendResult(False, f"{e.type}: {e.detail}")
        finally:
            # 9. 导航复位（评审 P2-2）：回到会话列表，下一轮 SCAN 才不会把聊天区当列表读
            if self._is_paused():
                self.log.info("人工暂停中，跳过 EXEC 导航复位")  # R5：不抢窗口 + 不误报复位失败
            else:
                try:
                    hwnd = self._window()
                    self._goto_conversation_list(hwnd)
                except ChannelError:
                    self.log.warning("EXEC 导航复位失败（下一轮 SCAN 会强制重新定位窗口）")

    def _chat_has_sent_bubble(self, hwnd: int, content: str) -> bool:
        """聊天区是否出现我方（右侧）新气泡（发送后校验判定式）。"""
        items = self._snap(hwnd, "after-send", self.chat_region)
        return self._row_has_text(items, content, right_only=True)

    def _row_has_text(self, items: list[dict], content: str, right_only: bool = False) -> bool:
        """按视觉行聚合 OCR 条目后做归一化包含比对（长文本 OCR 常断为多段）。

        right_only=True 时只接受「行左边界 ≥ 聊天区中线」的行（我方右侧气泡），
        用于发送后校验；比对前去掉空白与标点，容忍 OCR 标点差异与残留前缀
        （实测漏判根因：中文长气泡被 OCR 断为多段，逐条子串匹配永远命中不到）。

        纯标点内容（如「。。。」）归一化后为空，回退原文包含比对，避免验证恒为 False（与 webim 通道同源问题）。
        """
        norm = _normalize(content)
        raw = content.strip()
        if not norm and not raw:
            return False
        mid = (self.chat_region[0] + self.chat_region[2]) // 2 if right_only else None
        for r in _group_rows(items, y_tol=14):
            if mid is not None and r["x0"] < mid:
                continue
            text = "".join(r["texts"])
            if not norm:
                # 纯标点：粘贴前已清空输入框，原文包含比对误命中风险低
                if raw in text:
                    return True
                continue
            t = _normalize(text)
            # 双向包含：我方内容 ⊂ 行文本（残留前缀），或行文本 ⊂ 我方内容（OCR 漏读尾字）
            if norm in t or (len(t) >= 6 and t in norm):
                return True
        return False

    def _paste_and_verify(self, hwnd: int, content: str) -> bool:
        """清空输入框后粘贴 content，并等输入框区域出现 content（OCR 比对）。

        必须先清空：实测闲管家输入框会残留上一任务/上次粘贴文本，直接粘贴会拼接
        （残留「123」→「123您好，有货的，可以直接下单哦」）。验证用按行聚合 + 归一化
        包含匹配，容忍 OCR 断行/标点差异。等待上限＝原 delay+delay*3 盲睡之和。
        """
        self._check_paused()  # R5 闸门：人工暂停中不清空/不粘贴（保护人工草稿）
        win.clear_focused_input()
        win.paste_clipboard(content)
        self._uia_invalidate()
        return self._wait_until(lambda: self._row_has_text(
            self._snap(hwnd, "input-verify", self.input_region), content), self.delay * 4, interval=self.delay)
