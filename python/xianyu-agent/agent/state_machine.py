"""主循环状态机（03 号文档 §2：SCAN→READ→UPLOAD→EXEC→RESULT→WAIT；POLL 在独立线程）。

- 唯一可触碰 UI 的线程（03 评审 P2-3）；心跳线程只发 HTTP 读 SharedState 快照。
- POLL 独立 HTTP 线程（task-poll）：常驻长轮询领取回复任务 → 队列 → 唤醒本线程执行，
  25s 挂起不再串在 UI 线程后面（否则消息延迟固定 +40s）。
- 降级态（§2.2）：IDLE（连续 3 轮拿不到画面，锁屏）/ BLOCKED（登录页特征）/ PAUSED（R5 人工接管），
  mode 只由本类写；三态均停止 SCAN/EXEC，POLL 线程也停止领取任务，只做巡检，心跳照发。
- 游标（§5.4）在 SharedState.cursors；事件节流（§3.7）同类 60s 最多 1 次。
"""
from __future__ import annotations

import logging
import queue
import threading
import time
from datetime import datetime, timedelta

import requests

from agent.api_client import ApiClient, ApiError
from agent.channel.base import ChannelError
from agent.channel.factory import create_channel
from agent.shared_state import SharedState


class StateMachine:
    """主循环状态机（M3 步骤 4-5：SCAN/READ/UPLOAD/EXEC/RESULT；POLL 在 task-poll 线程；守护线程信号步骤 7 接入）。"""

    def __init__(self, cfg: dict, client: ApiClient, state: SharedState, audit,
                 shots_dir, log: logging.Logger):
        self.cfg = cfg
        self.client = client
        self.state = state
        self.audit = audit
        self.log = log
        self.channel = create_channel(cfg, log, audit, shots_dir)
        # 15 号文档：心跳上报通道能力（Java 据此拒绝不支持的媒体任务）
        self.state.capabilities = self.channel.capabilities()
        self.channel.on_progress = self.state.touch_main_loop  # 长扫描逐个会话报进度
        # R5 人工接管闸门：channel 每个 UI 动作前查该回调，命中即抛 ChannelError("paused") 中止
        self.channel.paused_check = lambda: self.state.ui_pause_requested
        self.poll_interval = float(cfg.get("poll", {}).get("interval_sec", 15))
        self.stop = threading.Event()
        self._event_last: dict[str, float] = {}          # 事件节流：type → 上次上报时间
        self._pending_upload: list = []                  # 上传失败待重试消息（03 §2.1 UPLOAD）
        self._scan_fail_streak = 0                       # SCAN 连续画面失败轮数（→ IDLE）
        # POLL 拆到独立 HTTP 线程（03 §2.1）：25s 长轮询挂起不再占住 UI 线程
        self._task_q: queue.Queue = queue.Queue()
        self._wake = threading.Event()                   # 有任务 → 唤醒 WAIT
        # P0-2（12 号文档）：SCAN 期间任务到达 → 通道在会话边界/列表收集步间提前收尾去 EXEC
        # （不可中断的通道/态保持原行为；paused 优先级高于 interrupted，由通道内保证）
        self.channel.interrupt_check = lambda: (not self._task_q.empty()) or self._wake.is_set()
        # P1-1（12 号文档 §六.1）：逐会话即读即传——webim 每读完一个会话立即回调上传，
        # 轮内尾部延迟消除（ui_ocr 不读该钩子，行为零变化）
        self.channel.on_messages = self._on_session_messages
        self._poll_thread: threading.Thread | None = None

    # ------------------------------------------------------------------ 主循环
    def run(self) -> None:
        """主循环入口（由 run_main 调用）。Ctrl+C 由外层处理，本方法负责停止。"""
        self.log.info("主循环启动（SCAN/READ/UPLOAD/EXEC/RESULT，WAIT %ss；POLL 独立线程）",
                      self.poll_interval)
        self._poll_thread = threading.Thread(target=self._poll_loop, name="task-poll", daemon=True)
        self._poll_thread.start()
        while not self.stop.is_set():
            self.state.touch_main_loop()
            try:
                self._tick()
            except Exception:
                self.log.exception("主循环异常（下轮继续）")
                self.stop.wait(min(self.poll_interval, 10))
        self.stop.set()
        if self._poll_thread is not None:
            self._poll_thread.join(timeout=5)
        self.log.info("主循环已停止")

    def _sleep(self, sec: float) -> None:
        """可被「来任务」打断的等待：stop 或 wake 任一命中即返回。"""
        deadline = time.perf_counter() + sec
        while not self.stop.is_set() and time.perf_counter() < deadline:
            if self._wake.wait(0.2):
                self._wake.clear()
                return

    def _tick(self) -> None:
        mode = self.state.mode
        if mode == "IDLE":
            self._idle_check()
            return
        if mode == "BLOCKED":
            self._blocked_check()
            return
        if mode == "PAUSED":
            self._paused_check()
            return
        # R5 人工接管：GUI 悬浮控制条的暂停请求信号 → 转 PAUSED（mode 仍由本类写）
        if self.state.ui_pause_requested:
            self._enter_paused()
            return
        # 守护线程锁屏信号（03 §6 异常 2 通道①）：由主循环转 IDLE（mode 单写原则）
        # P1-1：仅桌面依赖通道（ui_ocr）响应锁屏；headless 通道（webim）无桌面概念
        if self.state.desktop_locked and self.channel.needs_desktop:
            self._enter_idle("desktop_locked")
            return
        # NORMAL：SCAN/READ → UPLOAD → EXEC（任务来自 POLL 线程队列）→ WAIT
        t0 = time.perf_counter()
        self.channel.stats_reset()
        taken = self._drain_tasks()  # 扫描前先处理已到达任务（不等下一轮，端到端延迟 ≈ 一次扫描）
        self._scan_and_upload()
        if self.state.mode != "NORMAL":
            self.log.info("本轮耗时 %.1fs（降级退出，%s）", time.perf_counter() - t0,
                          self.channel.stats_summary())
            return  # 扫描期间降级（BLOCKED/IDLE），本轮不再 EXEC
        taken += self._drain_tasks()
        for task in taken:
            if self.state.mode != "NORMAL":
                break  # 降级：停止外发
            self.state.touch_main_loop()
            self._exec(task)
        self.log.info("本轮耗时 %.1fs（任务=%d 待上传=%d，%s）",
                      time.perf_counter() - t0, len(taken), len(self._pending_upload),
                      self.channel.stats_summary())
        self._sleep(self.poll_interval)

    def _drain_tasks(self) -> list[dict]:
        """取出队列中已领取的任务（任务轮询线程投入，03 §2.1 TAKE→EXEC）。"""
        tasks: list[dict] = []
        while True:
            try:
                tasks.append(self._task_q.get_nowait())
            except queue.Empty:
                return tasks

    # ------------------------------------------------------------------ SCAN/READ/UPLOAD
    def _scan_and_upload(self) -> None:
        # 1. 账号快照（心跳用，§5.2）
        # 2026-09-30 修复（vm101 静默漏读根因①）：read_accounts 成功只证明「页面还在 IM 页」
        # （holder/标题在），不证明「会话行渲染完成」——骨架屏停滞时两者同时成立。原实现
        # 在此处无条件清零 _scan_fail_streak，而失败计数紧接着在 read_unread 的 window_missing
        # 分支 +1 → 每轮被抹掉、永远到不了 3 → 永不进 IDLE → 页面/浏览器永不重建 → rows=0
        # 静默持续（09-30 02:01 起 7.5h+）。清零改到「本轮确实读到列表」之后（见方法末尾）。
        list_missing = False
        try:
            accounts = self.channel.read_accounts()
            self.state.accounts = accounts
        except ChannelError as e:
            if e.type == "paused":
                # R5 人工暂停（闸门中断）：本轮立即收尾转 PAUSED（不算失败）
                self._enter_paused()
                return
            if e.type == "window_missing":
                list_missing = True
                self._scan_fail_streak += 1
                self.log.warning("SCAN 拿不到画面（%d/3）: %s", self._scan_fail_streak, e.detail)
                if self._scan_fail_streak >= 3:
                    self._enter_idle("scan_fail")
                return
            self.log.warning("账号快照采集失败: %s", e)
            return
        if not accounts:
            return  # 无登录账号：无会话可扫
        # 2. 逐账号读未读（READ）
        for acc in accounts:
            if self.state.mode != "NORMAL":
                return
            cursor = self.state.account_cursor(acc["userNick"])
            try:
                msgs = self.channel.read_unread(acc, cursor)
            except ChannelError as e:
                if e.type == "paused":
                    # R5 人工暂停（闸门中断）：停止本轮读，转 PAUSED
                    self._enter_paused()
                    return
                if e.type == "login_required":
                    self._enter_blocked(acc)
                    return
                if e.type == "window_missing":
                    # 2026-09-30 修复（vm101 静默漏读根因②）：原实现只加计数、不落日志不报事件 →
                    # 失效静默无痕、管理端仍显示 online。此处必须留下可追溯证据。
                    list_missing = True
                    self._scan_fail_streak += 1
                    self.log.warning("账号 %s 会话列表不可用（%d/3）: %s",
                                     acc["userNick"], self._scan_fail_streak, e.detail)
                    self.audit.event("scan_window_missing", account=acc["userNick"],
                                     streak=self._scan_fail_streak, detail=str(e.detail)[:200])
                    self._event_throttled(acc, "client_error", "warn",
                                          f"账号 {acc['userNick']} 会话列表连续 "
                                          f"{self._scan_fail_streak} 轮不可用，等待通道自愈",
                                          {"kind": "conversation_list_not_ready",
                                           "streak": self._scan_fail_streak,
                                           "detail": str(e.detail)[:200]})
                    if self._scan_fail_streak >= 3:
                        self._enter_idle("scan_fail")
                    break  # 页面级异常：本轮不再读其余账号，先 flush 已读再收尾
                if e.type == "interrupted":
                    # P0-2：列表收集步间中断（本轮尚未读到新消息）——收尾：直进下方
                    # _flush_upload 上传已读，返回 _tick 后 drain → EXEC 优先发送
                    self.log.info("扫描被回复任务中断（%s），先上传已读再发送", e.detail)
                    break
                self.log.warning("账号 %s 扫描失败: %s", acc["userNick"], e)
                continue
            self._pending_upload.extend(msgs)
        # 2.5 画面失败计数清零：仅当本轮没有任何 window_missing（任一账号读到列表即算画面正常）。
        # 放在此处而非 read_accounts 之后，才能让 read_unread 的失败真正累计到 3（根因①）。
        if not list_missing:
            self._scan_fail_streak = 0
        # 3. UPLOAD（游标去重已在通道内完成，这里补时钟偏移 + 分批上传）
        self._flush_upload()

    def _on_session_messages(self, msgs: list) -> None:
        """P1-1（12 号文档 §六.1）即读即传：通道读完一个会话回调 → 入队并立即 flush。

        复用 _flush_upload（批 ≤200 不变；网络失败由其内部重试后留队列，轮末再试），
        不抛异常打断扫描。
        """
        self._pending_upload.extend(msgs)
        self._flush_upload()

    def _flush_upload(self) -> None:
        """批量上传（≤200/批，03 §2.1 UPLOAD）；网络失败重试 2 次后保留队列下轮再试。"""
        offset = self.state.clock_offset_sec
        while self._pending_upload:
            batch = self._pending_upload[:200]
            payload = [self._to_upload_item(m, offset) for m in batch]
            try:
                resp = self.client.upload(payload)
            except (requests.RequestException, ApiError) as e:
                self.log.warning("消息上传失败（重试 2 次后保留队列）: %s", e)
                if self._upload_retry(payload, batch):
                    break
                continue
            self._pending_upload = self._pending_upload[200:]
            failed = resp.get("failedCount") or 0
            if failed:
                self.log.warning("消息上传 %d 条被 Java 拒绝: %s",
                                 failed, resp.get("failures", []))
            self.log.info("消息上传: accepted=%s duplicate=%s failed=%s 剩余队列=%d",
                          resp.get("acceptedCount"), resp.get("duplicateCount"),
                          failed, len(self._pending_upload))
            self.audit.event("upload", accepted=resp.get("acceptedCount"),
                             duplicate=resp.get("duplicateCount"), failed=failed)

    def _upload_retry(self, payload: list[dict], batch: list) -> bool:
        """网络失败重试 2 次（指数退避 2s/5s）；成功返回 False（队列已消耗），仍失败返回 True。"""
        for delay in (2, 5):
            self.stop.wait(delay)
            try:
                resp = self.client.upload(payload)
            except (requests.RequestException, ApiError):
                continue
            self._pending_upload = self._pending_upload[len(batch):]
            self.log.info("消息上传重试成功: accepted=%s duplicate=%s",
                          resp.get("acceptedCount"), resp.get("duplicateCount"))
            return False
        return True

    @staticmethod
    def _to_upload_item(m, offset: float) -> dict:
        """Message → upload 请求条目（与 Java UploadReqVO.MessageItem 对齐）；
        接收时间加时钟偏移（03 §3.1），解析失败保持原值。

        ⚠️ 偏移按**整分粒度**应用：聊天区 OCR 时间只有分钟精度，若叠加亚分钟偏移
        （时钟校正实测 ±1s），整分估算会被推入上一分钟（10:40:00 − 1s → 10:39:59，
        比聊天区自己的「10:40」分隔行还早），实测导致 in 消息排到了 out 消息之前。
        """
        receive_time = m.receive_time
        offset_sec = round(offset / 60.0) * 60 if offset else 0
        if offset_sec:
            try:
                t = datetime.fromisoformat(m.receive_time) + timedelta(seconds=offset_sec)
                receive_time = t.isoformat(timespec="seconds")
            except ValueError:
                pass
        item = {
            "clientMsgId": m.client_msg_id,
            "accountKey": m.account_key,
            "buyerId": m.buyer_id,
            "buyerNick": m.buyer_nick,
            "content": m.content,
            "contentType": m.content_type,
            "direction": "in",
            "receiveTime": receive_time,
        }
        # 13 号文档 §4.1/§4.2：可选字段非空才带（chatId 只落库不聚合；itemId 协议
        # string；ext 商品卡快照键名契约见 13 号 §4.2）
        if m.chat_id:
            item["chatId"] = m.chat_id
        if m.item_id:
            item["itemId"] = m.item_id
        if m.ext:
            item["ext"] = m.ext
        # 会话级商品快照（图片/展示价文本/标题）：非空才带，Java 侧落列供管理端左栏/头部展示
        if m.product_image_url:
            item["productImageUrl"] = m.product_image_url
        if m.product_price_text:
            item["productPriceText"] = m.product_price_text
        # 标题（2026-09-24 会话唯一性：会话行 sessionInfo.itemInfo.title，未关联商品会话也能展示）
        if m.product_title:
            item["productTitle"] = m.product_title
        return item

    # ------------------------------------------------------------------ POLL（独立线程）
    def _poll_loop(self) -> None:
        """POLL 线程（03 §2.1）：常驻长轮询（服务端挂起 ≤25s），任务入队并唤醒主循环。

        拆出 UI 线程的理由：长轮询的 25s 挂起与 15s WAIT 串联在同一线程时，
        消息入库延迟被固定拉长到 40s；且 UI 线程不可被 HTTP 阻塞。
        降级态（IDLE/BLOCKED）**不领取任务**——与今天一致：Java 侧任务保持待领取，
        避免 claimed/dispatched 任务在本地堆积撞上 5 分钟超时兜底。
        """
        while not self.stop.is_set():
            if self.state.mode != "NORMAL" or (self.state.desktop_locked and self.channel.needs_desktop):
                self.stop.wait(3)
                continue
            try:
                tasks = self.client.poll_tasks()
            except (requests.RequestException, ApiError) as e:
                self.log.warning("长轮询异常（视为无任务，3s 后重连）: %s", e)
                self.stop.wait(3)
                continue
            except Exception:
                self.log.exception("长轮询未知异常（3s 后重试）")
                self.stop.wait(3)
                continue
            if tasks:
                for task in tasks:
                    self._task_q.put(task)
                self.log.info("POLL 领取任务 %d 条，唤醒主循环发送", len(tasks))
                self._wake.set()

    # ------------------------------------------------------------------ EXEC/RESULT
    def _exec(self, task: dict) -> None:
        """执行单个回复任务（03 §5.5）；结果映射见 03 §3.5。"""
        if self.state.ui_pause_requested:
            # R5：人工暂停请求已置位 → 不执行、不回传（任务留 Java 超时兜底），本轮收尾转 PAUSED
            self._enter_paused()
            return
        task_id = task.get("taskId")
        account = task.get("accountKey") or {}
        buyer = task.get("buyerNick") or ""
        content = task.get("content") or ""
        content_type = task.get("contentType") or "text"
        media = task.get("media") or None
        item_id = task.get("itemId")  # 13 号 §4.3：string 可空，随任务队列流到 send_message 定位
        known_items = task.get("knownItems") or []  # 13 号 §五 增强 B：会话已知商品集合，兜底放行旧商品回复
        self.log.info("EXEC 任务 #%s: 账号=%s 买家=%s 类型=%s 内容=%s%s 已知商品=%d",
                      task_id, account.get("userNick"), buyer, content_type, content[:30],
                      f" itemId={item_id}" if item_id else "", len(known_items))
        self.audit.event("send_begin", taskId=task_id, account=account.get("userNick"),
                         buyer=buyer, contentType=content_type)
        result = self.channel.send_message(account, buyer, content, item_id, known_items,
                                           content_type=content_type, media=media)
        if result.ok is True:
            verify_url = self._upload_verify_image(task_id, result.verify_image_path)
            detail = result.detail
            if result.verify_image_path and not verify_url:
                detail += "；截图上传失败，本地副本 " + result.verify_image_path
            self._report_result_retry(task_id, "success", detail, verify_url)
            self.audit.event("send", taskId=task_id, buyer=buyer, result="success",
                             verifyShot=result.verify_image_path)
        elif result.ok is False:
            self._report_result_retry(task_id, "failed", result.detail, "")
            self._event_throttled(account, "send_failed", "error", result.detail,
                                  {"taskId": task_id, "buyerNick": buyer})
            self.audit.event("send", taskId=task_id, buyer=buyer, result="failed",
                             detail=result.detail)
        else:
            # 验证不确定：不回传结果（03 §3.5），发 send_unknown（02 §4.5，Java 超时兜底）
            self._event_throttled(account, "send_unknown", "warn", result.detail,
                                  {"taskId": task_id, "buyerNick": buyer})
            self.audit.event("send", taskId=task_id, buyer=buyer, result="unknown",
                             detail=result.detail)
        # R5：发送过程中人工暂停（闸门中断 → result=failed「paused」已按实际回传）→ 转 PAUSED
        if self.state.ui_pause_requested:
            self._enter_paused()

    def _upload_verify_image(self, task_id, verify_path: str | None) -> str:
        """验证截图上传 OSS（03 §3.6）；失败重试 1 次，仍失败返回空串。"""
        if not verify_path:
            return ""
        for _ in range(2):
            try:
                return self.client.upload_file(verify_path, tag=f"task-{task_id}")
            except Exception as e:
                self.log.warning("验证截图上传失败（重试 1 次）: %s", e)
        return ""

    def _report_result_retry(self, task_id, status: str, msg: str, url: str) -> None:
        """结果回传（03 §2.1 RESULT）；网络失败重试 2 次，仍失败记日志（Java 5 分钟超时兜底）。"""
        for attempt in range(3):
            try:
                self.client.report_result(task_id, status, msg, url)
                self.log.info("任务 #%s 结果已回传: %s", task_id, status)
                return
            except (requests.RequestException, ApiError) as e:
                if attempt == 2:
                    self.log.error("任务 #%s 结果回传失败（交 Java 超时兜底）: %s", task_id, e)
                else:
                    self.stop.wait(2 + attempt * 3)

    # ------------------------------------------------------------------ 降级态
    def _enter_idle(self, reason: str) -> None:
        """进入 IDLE（03 §2.2）。reason: scan_fail（连续拿不到画面）/ desktop_locked（守护线程锁屏信号）。"""
        self.state.mode = "IDLE"
        self.state.screen_locked = True
        if reason == "desktop_locked":
            msg = "检测到系统锁屏（OpenInputDesktop）"
            event_content = "检测到系统锁屏，进入 IDLE"
        else:
            msg = "连续 3 轮拿不到账号会话列表/画面"
            event_content = "连续多轮无法获取账号会话列表（读取停滞，已进入自愈巡检）"
        self.log.warning("%s，进入 IDLE（心跳带 screenLocked=true）", msg)
        self._event_throttled(None, "screen_locked", "warn", event_content, None)

    def _idle_check(self) -> None:
        """IDLE 巡检：每 10s 尝试拿画面；画面可用且桌面未锁屏 → 回 NORMAL（游标不重置，锁屏期间消息照常增量读）。"""
        if self.state.desktop_locked and self.channel.needs_desktop:
            self.stop.wait(10)
            return
        try:
            self.channel.health_check()  # ui_ocr=定位+前台化即算画面可用；webim=浏览器探测
            self.state.mode = "NORMAL"
            self.state.screen_locked = False
            self._scan_fail_streak = 0
            self.log.info("画面恢复，退出 IDLE")
            return
        except ChannelError:
            pass
        self.stop.wait(10)

    # ------------------------------------------------------------------ 人工接管（R5）
    def _enter_paused(self) -> None:
        """进入 PAUSED（07 号文档 R5 人工接管）：VM 端悬浮控制条点「暂停」。

        停 SCAN/EXEC（POLL 线程见 mode != NORMAL 自动停止领任务），心跳照发（管理端不掉线）；
        恢复：再点按钮（信号置假，_paused_check 消费）或超时自动恢复（control.auto_resume_min）。
        幂等：已 PAUSED 直接返回（多处调用点可能重复触发）。
        """
        if self.state.mode == "PAUSED":
            return
        self.state.mode = "PAUSED"
        self.state.paused_at = time.time()
        self.log.warning("人工接管：悬浮控制条请求暂停，停止扫描与发送（心跳继续）")
        self.audit.event("human_paused")
        self._event_throttled(None, "human_paused", "warn",
                              "人工在虚拟机端暂停 Agent（悬浮控制条），已停止读取与发送", None)

    def _paused_check(self) -> None:
        """PAUSED 巡检：恢复请求（信号被悬浮条置假）→ 回 NORMAL；超时 → 自动恢复。"""
        if not self.state.ui_pause_requested:
            self._resume_paused("manual")
            return
        auto_min = float((self.cfg.get("control") or {}).get("auto_resume_min", 30) or 0)
        if auto_min > 0 and time.time() - self.state.paused_at >= auto_min * 60:
            self._resume_paused("auto_timeout")
            return
        self.stop.wait(5)

    def _resume_paused(self, reason: str) -> None:
        """退出 PAUSED 回 NORMAL；reason: manual（再点按钮）/ auto_timeout（超时兜底）。"""
        duration = int(time.time() - self.state.paused_at) if self.state.paused_at else 0
        self.state.mode = "NORMAL"
        self.state.ui_pause_requested = False   # 消费方复位信号（悬浮条按钮文案随之同步）
        self.state.paused_at = 0.0
        if reason == "auto_timeout":
            self.log.warning("人工暂停超时（%ds）自动恢复扫描与发送（control.auto_resume_min）", duration)
            self.audit.event("human_auto_resumed", durationSec=duration)
            self._event_throttled(None, "human_resumed", "warn",
                                  f"人工暂停已超时，Agent 自动恢复（暂停 {duration}s）",
                                  {"reason": "auto_timeout"})
        else:
            self.log.info("人工恢复（悬浮控制条），恢复扫描与发送（暂停 %ds）", duration)
            self.audit.event("human_resumed", durationSec=duration)
            self._event_throttled(None, "human_resumed", "info",
                                  f"人工恢复 Agent（暂停 {duration}s）", {"reason": "manual"})

    def _enter_blocked(self, account: dict) -> None:
        self.state.mode = "BLOCKED"
        self.state.accounts = []
        self.log.warning("闲管家出现登录/扫码特征，进入 BLOCKED（停止外发，等人工登录）")
        self._event_throttled(account, "login_expired", "warn",
                              "闲管家出现登录/扫码特征，等待人工登录", None)
        self.audit.event("blocked", reason="login_required")

    def _blocked_check(self) -> None:
        """BLOCKED 巡检：每 10s 尝试读账号快照；恢复 → 回 NORMAL 并全量重扫（03 §2.2）。"""
        try:
            accounts = self.channel.read_accounts()
            if accounts:
                self.state.accounts = accounts
                self.state.mode = "NORMAL"
                self.state.cursors = {}   # 全量重扫：游标重置重新建基线（不报登录前历史）
                self.log.info("登录状态恢复，退出 BLOCKED，全量重扫")
                return
        except ChannelError:
            pass
        self.stop.wait(10)

    # ------------------------------------------------------------------ 事件上报（节流）
    def _event_throttled(self, account_key: dict | None, event_type: str, level: str,
                         content: str, raw: dict | None) -> None:
        """同类事件 60s 内最多上报 1 次（03 §3.7，锁屏巡检 10s 一次只报进入事件）。"""
        now = time.time()
        if now - self._event_last.get(event_type, 0.0) < 60:
            return
        self._event_last[event_type] = now
        try:
            self.client.report_event(account_key, event_type, level, content, raw)
        except Exception as e:
            self.log.warning("事件上报失败（下轮节流窗口后重试）: %s", e)
