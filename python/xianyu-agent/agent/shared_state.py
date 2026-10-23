"""线程共享状态（03 号文档 §1.1 / §3.2 / §5.4）。

- 主线程（主循环）写，心跳/守护线程只读；单写原则（03 决策 D-03-5），GIL 下无需加锁。
- 心跳线程读 accounts 快照时取 list 浅拷贝，避免读到主循环替换列表的半程状态。
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class ConvCursor:
    """单会话内存游标（03 §5.4）：防重报 + 会话列表变化检测。

    - 新消息判定：receive_time > last_receive_time，或时间不新但内容 hash 未见过
      （OCR 时间估算误差由宽容规则消化，Java 窗口判重兜底）。
    - last_receive_time 首读设 now：后续同内容重读被 hash+时间规则丢弃。
    """
    last_receive_time: datetime | None = None   # 已见最大 receive_time（校正前本地时间）
    content_hashes: set = field(default_factory=set)   # 已上报内容 hash
    last_summary_hash: str | None = None        # 最近一次读取时该会话的摘要 hash（is_new_conv 判据）
    summary_hashes: set = field(default_factory=set)   # 该昵称已见过的摘要 hash 集合（SCAN 变化检测）
    baselined: bool = False                     # 本进程内是否读过该会话聊天区（首次读走历史防重放规则）
    drained: bool = False                       # 上次读完无新消息：未读角标常驻（红点不消）时抑制每轮重开
    last_unread_count: int = 0                  # 最近一次扫描的未读角标数（数增加 = 有新消息，防同文摘要漏检）
    # —— 07 号文档 R1：读取可靠性 ——
    last_time_est: datetime | None = None       # T1.1 会话行相对时间估计（时间前进 = 有新消息强信号）
    last_time_text_norm: str | None = None      # T1.1 时间文本归一化值（time_est 解析失败时的兜底比较）
    last_forced_open: datetime | None = None    # T1.2 最近一次兜底强制打开时刻（轮转选择用）
    open_fail_streak: int = 0                   # T1.4 打开会话连续失败次数（成功清零）
    blacklisted_until: datetime | None = None   # T1.4 熔断黑名单到期时刻（None=未熔断）
    blacklist_strikes: int = 0                  # T1.4 累计入狱次数（TTL 翻倍控制，出狱成功清零）
    # —— 2026-09-24 会话唯一性：游标按会话唯一 id（chat_id）分组，昵称仅作展示/兜底 ——
    conv_id: str | None = None                  # 本游标对应的会话唯一 key（chat_id；无 chat_id 时为 'noncid:<昵称>'）
    buyer_nick: str | None = None               # 买家昵称（同一买家多商品会话共享，仅供日志/展示）


@dataclass
class AccountCursor:
    """单账号游标集：convId → ConvCursor（2026-09-24 会话唯一性：键由昵称改为会话唯一 id）。

    initialized：进程首轮扫描只建基线（记 summary hash）不读聊天区，
    防首次运行把全部历史消息上报；运行中新增会话则读并上报近 10 分钟消息。
    started_at：进程首轮扫描时刻，作为本进程消息时间下界——早于它的消息一律视为
    历史（重启后摘要抖动触发的整会话重读由此拦住，不再把旧消息当新消息上报）。
    """
    convs: dict = field(default_factory=dict)
    initialized: bool = False
    started_at: datetime | None = None
    scan_rounds: int = 0    # 本进程该账号累计扫描轮数（T1.2 兜底读取的轮次周期用）

    def conv(self, key: str, buyer_nick: str | None = None) -> ConvCursor:
        """按会话唯一 key 取/建游标（2026-09-24 会话唯一性修复）。

        key：chat_id（= 会话行 sessionInfo.sessionId）优先；无 chat_id 的存量/异常行退
        'noncid:<昵称>'（与 Java convId 口径一致）。旧实现仅用昵称作键，同一买家对多个
        商品的会话共享昵称 → 多个会话被合并成一个游标，只会读到其中一个。
        buyer_nick：仅作日志/展示（同一 key 下昵称不会变）。
        """
        c = self.convs.get(key)
        if c is None:
            c = ConvCursor(conv_id=key, buyer_nick=buyer_nick)
            self.convs[key] = c
        elif buyer_nick and not c.buyer_nick:
            c.buyer_nick = buyer_nick
        return c


@dataclass
class SharedState:
    """主循环/任务轮询/心跳/守护四线程共享的运行时状态（单写原则：03 §1.1、§2.2）。"""

    # 运行模式（02 降级态仲裁）：NORMAL / IDLE(锁屏) / BLOCKED(登录失效)
    # 单写原则：mode 只由主循环写，守护线程只发信号（03 §2.2）
    mode: str = "NORMAL"
    # 桌面锁屏信号：守护线程 OpenInputDesktop 检测写入（03 §6 异常 2 通道①），
    # 主循环每轮开头读到后自行转 IDLE（mode 仍由主循环写）
    desktop_locked: bool = False
    # IDLE 态锁屏标记：主循环写（进入/退出 IDLE），心跳读取上报 screenLocked
    screen_locked: bool = False
    # 本机已登录闲鱼号快照 [{userNick, authorizeId, loginStatus}]（步骤 4 主循环采集）
    accounts: list = field(default_factory=list)
    # 心跳能力声明（15 号文档）：text / image / emoji；由通道按 outbound 开关设置
    capabilities: list = field(default_factory=lambda: ["text"])
    # 心跳注册返回的 Agent ID（注册或已有）
    agent_id: int | None = None
    # 时钟漂移校正：offset = serverTime - 本地时间（秒），receiveTime 估算最终加此值（03 §3.1）
    clock_offset_sec: float = 0.0
    # 服务端建议心跳周期（秒）；0 = 尚未从心跳响应拿到（用本地配置初值兜底）
    poll_interval_sec: float = 0.0
    # 心跳线程健康戳（守护线程巡检用，03 §6 异常 8）
    last_heartbeat_at: float = 0.0
    heartbeat_ok: bool = False
    # 主循环健康戳（守护线程巡检用，03 §6 异常 9）
    main_loop_beat_at: float = 0.0
    # 当前窗口导航状态（如已进入某账号会话列表），EXEC 结束复位（03 §5.5 步骤 9，步骤 4/5 用）
    nav_state: dict = field(default_factory=dict)
    # 会话游标集：accountNick → AccountCursor（仅主循环读写，03 §5.4）
    cursors: dict = field(default_factory=dict)
    # —— 07 号文档 R5：人工接管（VM 端悬浮控制条） ——
    # 暂停请求信号：GUI 悬浮控制条写（点击暂停/恢复），主循环消费后切 mode
    # （与 desktop_locked 同 handshake 模式；mode 单写原则不破：本字段只是「请求」）
    ui_pause_requested: bool = False
    # 暂停开始时刻（主循环进 PAUSED 时写；超时自动恢复 control.auto_resume_min 用）
    paused_at: float = 0.0

    def touch_main_loop(self) -> None:
        self.main_loop_beat_at = time.time()

    def account_cursor(self, account_nick: str) -> AccountCursor:
        c = self.cursors.get(account_nick)
        if c is None:
            c = AccountCursor()
            self.cursors[account_nick] = c
        return c

    def account_snapshot(self) -> list:
        """账号快照浅拷贝：心跳线程读取用（主线程可能随时整体替换列表）。"""
        return list(self.accounts)
