"""消息通道抽象（03 号文档 §4，01 §5.3 落地）。

- 当前实现 `ui_ocr.py`：UIA 优先读 + OCR 兜底；发送走「定位输入框 → 剪贴板粘贴 →
  截图验证 → 点发送 → 截图再验证」（03 §5）。
- 未来官方消息 API 落地 → 新增 `official_api.py` 实现同接口，替换 config 的
  `channel.impl` 即可，api_client/状态机零改动。
- 红线：UI 坐标、OCR 细节禁止泄漏进 Java 侧协议字段（协议只传业务语义）。
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class Message:                    # 与 02 §4.2 upload 消息字段一一对应
    client_msg_id: str
    account_key: dict             # { "userNick": str, "authorizeId": str|None }
    buyer_id: str | None
    buyer_nick: str
    content: str
    content_type: str             # text/image/emoji/audio/video/order/card/other
    receive_time: str             # ISO-8601，已含时钟校正
    raw: dict = field(default_factory=dict)   # OCR/UIA 原始行（审计）
    chat_id: str | None = None    # 13 号文档 §4.1：WS 会话 id（机会性，可空；只落库不聚合）
    item_id: str | None = None    # 13 号文档 §4.1：会话绑定闲鱼商品 id（协议 string，可空）
    ext: dict | None = None       # 13 号 §4.2 结构化快照：商品卡 type=product / 媒体 type=media（items+sourceMessageId）
    product_image_url: str | None = None   # 会话级商品快照-主图（会话行右侧商品缩略图，可空）
    product_price_text: str | None = None  # 会话级商品快照-展示价文本（最近商品卡价格，如 ¥1.00，可空）
    product_title: str | None = None       # 会话级商品快照-商品标题（会话行 sessionInfo.itemInfo.title，可空）


@dataclass
class SendResult:
    ok: bool                      # True=验证成功；False=验证失败；None=不确定
    detail: str                   # resultMsg
    verify_image_path: str | None = None   # 本地验证截图路径（03 §5.5 步骤 8）


class ChannelError(Exception):
    """通道层可预期失败（窗口找不到/登录页特征/账号不存在等），携带语义 type。"""

    def __init__(self, type_: str, detail: str = ""):
        super().__init__(f"[{type_}] {detail}")
        self.type = type_   # login_required / window_missing / account_missing / buyer_missing / interrupted
        self.detail = detail


class Channel(ABC):
    """消息通道接口：read_accounts / read_unread / send_message（03 §4）。

    可选钩子（跨通道统一契约，10 号文档 M0）：
    - `needs_desktop`：True=依赖交互桌面（ui_ocr）；False=headless 运行（webim）。
      状态机 desktop_locked 三处阻断按此条件化（11 号评审 P1-1）。
    - `on_progress` / `paused_check`：状态机注入的回调（长扫描进度心跳 / R5 人工暂停闸门）。
    - `on_messages`：状态机注入的逐会话即读即传回调（12 号文档 P1-1：入参 List[Message]，
      通道每读完一个会话即调用）；默认 None=轮末统一返回（ui_ocr 零变化）。
    - `interrupt_check`：状态机注入的 SCAN 可中断钩子（12 号文档 P0-2：回复任务到达时
      在会话边界/列表收集步间提前收尾扫描去 EXEC）；默认 None=不可中断（ui_ocr 零变化）。
    - `warmup` / `health_check` / `stats_reset` / `stats_summary`：生命周期与可观测性，
      默认空实现；health_check 失败须抛 ChannelError（IDLE 巡检恢复判定）。
    """

    needs_desktop: bool = True
    on_progress = None      # callable | None（状态机注入 touch_main_loop）
    paused_check = None     # callable | None（状态机注入 ui_pause_requested 判定）
    on_messages = None      # callable | None（状态机注入逐会话即读即传；默认轮末统一返回）
    interrupt_check = None  # callable | None（状态机注入任务到达判定；默认不可中断）

    @abstractmethod
    def read_accounts(self) -> list[dict]:
        """本机已登录账号快照（心跳用）：[{userNick, authorizeId, loginStatus}]。"""

    @abstractmethod
    def read_unread(self, account: dict, cursor) -> list[Message]:
        """读某账号下所有会话的新消息（游标过滤由状态机负责，通道只管读）。

        account: {userNick, authorizeId}；cursor: 该账号的会话游标（状态机持有）。
        返回按会话时间升序的 in 消息列表（buyerNick 已填）。
        """

    @abstractmethod
    def send_message(self, account: dict, buyer_nick: str, content: str,
                     item_id: str | None = None,
                     known_items: list[str] | None = None,
                     content_type: str = "text",
                     media: dict | None = None) -> SendResult:
        """发送 + 验证（03 §5.5 全流程，含步骤 2.5 身份校验与步骤 9 导航复位）。

        item_id（13 号文档 §五）：回复任务带闲鱼商品 id 时的定位弱校验入参，
        仅 webim 通道消费（比对会话可见商品卡 item 集合）；ui_ocr 忽略。
        known_items（13 号 §五 增强 B）：该买家×账号会话库中出现过的商品集合，
        供 webim 通道在目标卡不在可视窗口时兜底放行；ui_ocr 忽略。

        content_type/media（15 号文档）：text/image/emoji；媒体任务由 webim 消费：
        image 需要 media.url，emoji 需要 media.alt。ui_ocr 仅支持 text。
        返回三态：True=已验证；False=未发出；None=可能已发出但无法验证（绝不重发）。
        """

    # ------------------------------------------------------------------ 可选钩子（默认空实现）
    def warmup(self) -> None:
        """进程启动预热（ui_ocr=OCR 模型预热；webim=浏览器启动），默认无操作。"""

    def health_check(self) -> None:
        """IDLE 巡检：探测通道可用性，失败抛 ChannelError（window_missing 等）。

        ui_ocr = 定位+前台化主窗口；webim = 浏览器进程/调试口/页面探测。
        """

    def stats_reset(self) -> None:
        """每轮开始清统计，默认无操作。"""

    def stats_summary(self) -> str:
        """单行统计文本（本轮耗时日志用），默认空串。"""
        return ""

    def capabilities(self) -> list[str]:
        """心跳能力声明（15 号文档）：默认仅 text；webim 按 outbound 开关追加 image/emoji。"""
        return ["text"]
