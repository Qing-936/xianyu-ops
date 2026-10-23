"""守护线程（03 号文档 §6 / §6.1：异常矩阵与自愈巡检）。

- 只做 API 级检测（OpenInputDesktop / psutil / 时间戳），不截图、不 OCR、不模拟输入
  （03 评审 P2-3）；UI 操作只允许主循环线程。
- 与主循环的仲裁（03 §2.2）：mode 只由主循环写，本线程只发信号——
  锁屏检测结果写 SharedState.desktop_locked，主循环每轮开头读到后自行转 IDLE。
- 巡检项（周期独立计时，config guard.*，03 §6.1）：
  1. 锁屏检测（10s）：OpenInputDesktop API（§6 异常 2 通道①；通道②「SCAN 连续 3 轮
     拿不到画面」由主循环自行判定）。
  2. 闲管家进程存活（10s，仅 impl=ui_ocr）：psutil 按 ui.proc_name 查进程；消失 → client_error（60s 节流），
     MVP 不自动拉起（§6 异常 3）；webim 为 headless 通道不依赖闲管家进程，跳过该项（M3 灰度切换后实测发现
     误报：webim 下每 60s 向 Java 上报 client_error 误导性告警）。
  3. 心跳线程健康（10s）：心跳戳超过 3×心跳周期未更新且线程已死 → 重启心跳线程 +
     client_error（§6 异常 8）；线程存活但戳过期只记日志（可能卡在 HTTP 请求，超时自愈）。
  4. 主循环健康（30s）：循环戳超过 5min 未更新 → 记 error 日志 + 审计后退出进程
     （§6 异常 9，MVP 直接 exit，靠任务计划/手动拉起）。
  5. logs/shots 滚动清理（每小时）：shots_retention_days + audit_retention_days。
"""
from __future__ import annotations

import ctypes
import logging
import os
import threading
import time
from pathlib import Path


class Guard:
    """守护巡检线程（M3 步骤 7）。"""

    def __init__(self, cfg: dict, client, state, audit, log: logging.Logger,
                 spawn_heartbeat, hb_ref: list, shots_dir: Path):
        g = cfg.get("guard", {})
        self.cfg = cfg
        self.client = client
        self.state = state
        self.audit = audit
        self.log = log
        self.spawn_heartbeat = spawn_heartbeat   # 心跳线程重启入口（main.run_main 闭包）
        self.hb_ref = hb_ref                     # [Thread|None]，spawn 后回写
        self.shots_dir = Path(shots_dir)
        self.screen_locked_check_sec = float(g.get("screen_locked_check_sec", 10))
        self.proc_alive_check_sec = float(g.get("proc_alive_check_sec", 10))
        self.heartbeat_health_sec = float(g.get("heartbeat_health_sec", 10))
        self.main_loop_health_sec = float(g.get("main_loop_health_sec", 30))
        # 主循环「整轮无进度」上限（原硬编码 300s，实测单轮最长可达 258s → 濒临误杀）
        self.main_loop_stale_sec = float(g.get("main_loop_stale_sec", 90))
        self.shots_retention_days = int(g.get("shots_retention_days", 7))
        self.proc_name = str(cfg.get("ui", {}).get("proc_name", ""))
        # 闲管家进程巡检仅 ui_ocr 通道有意义（webim headless 不依赖闲管家；不条件化会误报 client_error，
        # 2026-09-22 M3 灰度切换实测发现：webim 下每 60s 报「闲管家进程不存在」+ 向 Java 误上报）
        self.proc_check_enabled = str((cfg.get("channel") or {}).get("impl") or "ui_ocr") == "ui_ocr"
        self._event_last: dict[str, float] = {}  # client_error 节流：kind → 上次上报时间

    # ------------------------------------------------------------------ 主循环
    def run(self, stop: threading.Event) -> None:
        """守护巡检循环（1s 步进，各巡检项独立周期）。

        单次巡检异常只记日志不杀线程（守护线程死掉会导致所有巡检静默停摆，
        锁屏演练实测：进程抖动引发巡检异常）。
        """
        last: dict[str, float] = {}
        self.log.info("守护线程启动（锁屏 %ss / 进程 %ss / 心跳健康 %ss / 主循环健康 %ss）",
                      self.screen_locked_check_sec, self.proc_alive_check_sec,
                      self.heartbeat_health_sec, self.main_loop_health_sec)
        while not stop.is_set():
            now = time.time()
            if now - last.get("lock", 0) >= self.screen_locked_check_sec:
                last["lock"] = now
                self._safe("锁屏检测", self._check_lock)
            if self.proc_check_enabled and now - last.get("proc", 0) >= self.proc_alive_check_sec:
                last["proc"] = now
                self._safe("进程存活", self._check_proc)
            if now - last.get("hb", 0) >= self.heartbeat_health_sec:
                last["hb"] = now
                self._safe("心跳健康", self._check_heartbeat)
            if now - last.get("ml", 0) >= self.main_loop_health_sec:
                last["ml"] = now
                self._safe("主循环健康", self._check_main_loop)
            if now - last.get("cleanup", 0) >= 3600:
                last["cleanup"] = now
                self._safe("滚动清理", self._cleanup_shots)
            stop.wait(1)
        self.log.info("守护线程已停止")

    def _safe(self, name: str, fn) -> None:
        """巡检项兑底：异常记日志，不中断巡检循环。"""
        try:
            fn()
        except Exception:
            self.log.exception("守护巡检[%s]异常（下周期重试）", name)

    # ------------------------------------------------------------------ 巡检项
    def _check_lock(self) -> None:
        """锁屏检测（OpenInputDesktop）；结果写 desktop_locked，由主循环消费转 IDLE。"""
        locked = _desktop_locked()
        self.log.info("锁屏巡检: locked=%s", locked)
        if locked == self.state.desktop_locked:
            return
        self.state.desktop_locked = locked
        self.log.warning("系统锁屏状态变化: %s（主循环将转 IDLE）", "已锁屏" if locked else "已解锁")
        self.audit.event("guard_desktop", locked=locked)

    def _check_proc(self) -> None:
        """闲管家进程存活（03 §6 异常 3）：消失只告警，MVP 不自动拉起。"""
        if _proc_alive(self.proc_name):
            return
        if self._event_last.get("proc_missing", 0) + 60 > time.time():
            return
        self._event_last["proc_missing"] = time.time()
        self.log.warning("闲管家进程「%s」不存在（MVP 不自动拉起，每 %ss 重检）",
                         self.proc_name, self.proc_alive_check_sec)
        self._report_client_error("闲管家进程消失，等待人工启动", {"kind": "proc_missing"})

    def _check_heartbeat(self) -> None:
        """心跳线程健康（03 §6 异常 8）：戳过期（>3×心跳周期）且线程已死 → 重启 + client_error。"""
        if self.state.last_heartbeat_at <= 0:
            return  # 首轮心跳完成前不判定（冷启动保护）
        interval = self.state.poll_interval_sec or float(
            self.cfg.get("heartbeat", {}).get("interval_sec", 15))
        stale_sec = time.time() - self.state.last_heartbeat_at
        if stale_sec <= 3 * interval:
            return
        hb = self.hb_ref[0]
        if hb is not None and hb.is_alive():
            self.log.warning("心跳戳过期 %.0fs 但线程存活（可能卡在 HTTP 请求，超时自愈）", stale_sec)
            return
        self.log.error("心跳线程已死且戳过期 %.0fs，重启心跳线程", stale_sec)
        self.audit.event("guard_heartbeat_restart", lastBeatAgo=int(stale_sec))
        self._report_client_error("心跳线程异常，已自动重启", {"kind": "heartbeat_restart"})
        self.spawn_heartbeat()

    def _check_main_loop(self) -> None:
        """主循环健康（03 §6 异常 9）：循环戳超过 guard.main_loop_stale_sec 未更新 → 审计后退出进程。"""
        if self.state.main_loop_beat_at <= 0:
            return  # 主循环尚未开始（冷启动保护）
        stale_sec = time.time() - self.state.main_loop_beat_at
        if stale_sec <= self.main_loop_stale_sec:
            return
        self.log.error("主循环卡死超过 %.0fs（实测 %.0fs），退出进程（03 §6 异常 9，靠任务计划/手动拉起）",
                       self.main_loop_stale_sec, stale_sec)
        self.audit.event("guard_main_loop_dead", staleSec=int(stale_sec))
        os._exit(1)

    def _cleanup_shots(self) -> None:
        """logs/shots 滚动清理（每小时，03 §6.1）：过期截图 + 审计文件。"""
        cutoff = time.time() - self.shots_retention_days * 86400
        deleted = 0
        if self.shots_dir.exists():
            for p in self.shots_dir.rglob("*"):
                try:
                    if p.is_file() and p.stat().st_mtime < cutoff:
                        p.unlink()
                        deleted += 1
                except OSError:
                    pass
        deleted += self.audit.cleanup()
        if deleted:
            self.log.info("滚动清理: 删除 %d 个过期文件（shots %d 天 / audit %d 天）",
                          deleted, self.shots_retention_days, self.audit.retention_days)

    # ------------------------------------------------------------------ 事件
    def _report_client_error(self, content: str, raw: dict | None) -> None:
        """client_error 事件（02 §4.5）；失败只记日志，不影响巡检。"""
        try:
            self.client.report_event(None, "client_error", "warn", content, raw)
        except Exception as e:
            self.log.warning("client_error 事件上报失败: %s", e)


# ---------------------------------------------------------------------- 平台检测
def _desktop_locked() -> bool:
    """系统锁屏检测（03 §6 异常 2 通道①）。

    通道①-a：OpenInputDesktop 返回 NULL（Win+L 锁屏）；
    通道①-b：前台窗口标题含锁屏特征。实测 Windows 锁屏安全桌面下
      OpenInputDesktop 仍返回句柄（单靠 API 漏判 → 截图读到锁屏画面、
      SCAN 反复 nav_missing），故补前台标题检测。非 Windows 平台恒 False。
    """
    try:
        user32 = ctypes.windll.user32
    except (AttributeError, OSError):
        return False
    h = user32.OpenInputDesktop(0, False, 0x0100)  # DESKTOP_SWITCHDESKTOP
    if not h:
        return True
    user32.CloseDesktop(h)
    try:
        import win32gui
        title = win32gui.GetWindowText(win32gui.GetForegroundWindow())
        if any(kw in title for kw in ("锁屏", "Lock Screen")):
            return True
    except Exception:
        pass
    return False


def _proc_alive(proc_name: str) -> bool:
    """按进程名关键字查找目标进程是否存在。

    防御：迭代期间进程可能已死，psutil 对已死进程的 info 可能为 None（锁屏/解锁时
    系统进程抖动实测触发），必须容错。
    """
    if not proc_name:
        return True
    import psutil  # 延迟加载：--check-config / --smoke 路径不引入
    for p in psutil.process_iter(["name"]):
        try:
            info = p.info or {}
            if proc_name in (info.get("name") or ""):
                return True
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    return False
