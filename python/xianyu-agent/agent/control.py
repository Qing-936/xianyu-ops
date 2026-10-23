"""VM 端人工接管悬浮控制条（07 号文档 R5）。

- 屏幕右下角一个小条：「暂停」/「恢复」点击切换（进程不退、心跳保持、POLL 不领任务）；
- 只写 SharedState.ui_pause_requested 请求信号，mode 由主循环消费后切换（mode 单写原则不破）；
- tkinter 不可用（无桌面会话/未安装）时 fail-open：记 warning 返回 False，Agent 照常运行；
- 位置默认屏幕右下角：不与任何 OCR region 重叠（mss 只抓固定 region，零截图污染）；RDP 进入/退出
  导致屏幕分辨率变化时，自动重新贴回右下角并重申置顶（防按钮跑出可视区）；
- tkinter 只支持主线程 mainloop → 调用方须在主线程 run()（状态机已挪至后台线程）。
"""
from __future__ import annotations

import logging


class FloatingControl:
    """人工接管悬浮控制条（R5）：点击 = 暂停/恢复 Agent。"""

    WIDTH = 96
    HEIGHT = 34
    COLOR_RUNNING = "#5b5bd6"   # 运行中（点击暂停）——品牌色 Deep Violet
    COLOR_PAUSED = "#d46b08"    # 暂停中（点击恢复）

    def __init__(self, cfg: dict, state, log: logging.Logger):
        c = cfg.get("control") or {}
        self.enable = bool(c.get("enable", True))
        self.position = c.get("position") or None
        self.state = state
        self.log = log
        self._root = None
        self._label = None
        self._last_screen = None

    def run(self) -> bool:
        """阻塞运行悬浮条（tkinter mainloop）。返回 False = 不可用，调用方退化处理。"""
        if not self.enable:
            self.log.info("control.enable=false，悬浮控制条未启用")
            return False
        try:
            import tkinter as tk
        except Exception as e:  # 环境缺 tkinter
            self.log.warning("tkinter 不可用（%s），悬浮控制条禁用（Agent 正常运行）", e)
            return False
        try:
            root = tk.Tk()
        except Exception as e:  # 无桌面会话等
            self.log.warning("悬浮窗创建失败（%s），悬浮控制条禁用", e)
            return False
        self._root = root
        root.overrideredirect(True)         # 无边框、不进任务栏
        root.attributes("-topmost", True)   # 常驻最前（不被闲管家置顶 trick 长期压住）
        try:
            root.attributes("-alpha", 0.88)
        except Exception:
            pass
        if self.position and len(self.position) == 2:
            x, y = int(self.position[0]), int(self.position[1])
        else:
            size = self._screen_size() or (root.winfo_screenwidth(),
                                           root.winfo_screenheight())
            x = size[0] - self.WIDTH - 24
            y = size[1] - self.HEIGHT - 72  # 上移避开任务栏
        root.geometry(f"{self.WIDTH}x{self.HEIGHT}+{x}+{y}")
        self._last_screen = self._screen_size()
        label = tk.Label(root, text="", font=("Microsoft YaHei UI", 11),
                         fg="#ffffff", bg=self.COLOR_RUNNING, cursor="hand2")
        label.pack(fill="both", expand=True)
        label.bind("<Button-1>", self._on_click)
        self._label = label
        self._refresh()
        root.after(1000, self._tick)
        self.log.info("悬浮控制条已启动（%dx%d @ %d,%d）：点击切换暂停/恢复",
                      self.WIDTH, self.HEIGHT, x, y)
        try:
            root.mainloop()
        except KeyboardInterrupt:
            self.log.info("悬浮控制条收到 Ctrl+C，退出")
        finally:
            try:
                root.destroy()
            except Exception:
                pass
        return True

    def _on_click(self, _event=None) -> None:
        """点击：翻转暂停请求信号（由主循环消费；立即刷新文案给出操作反馈）。"""
        self.state.ui_pause_requested = not self.state.ui_pause_requested
        self.log.info("悬浮控制条点击：%s", "请求暂停" if self.state.ui_pause_requested else "请求恢复")
        self._refresh()

    def _tick(self) -> None:
        """每秒刷新（主循环手动恢复/超时自动恢复后文案自动同步；分辨率变化时重新贴位）。"""
        self._refresh()
        self._keep_on_screen()
        if self._root is not None:
            self._root.after(1000, self._tick)

    def _keep_on_screen(self) -> None:
        """RDP 进入/退出导致屏幕分辨率变化时，重新贴回右下角并重申置顶（防按钮跑出可视区）。"""
        root = self._root
        if root is None or self.position:
            return
        size = self._screen_size()
        if size is None or size == self._last_screen:
            return
        w, h = size
        old = self._last_screen
        self._last_screen = size
        x = w - self.WIDTH - 24
        y = h - self.HEIGHT - 72
        try:
            root.geometry(f"{self.WIDTH}x{self.HEIGHT}+{x}+{y}")
            root.attributes("-topmost", True)
        except Exception:
            return
        self.log.info("屏幕分辨率变化 %s→%s，悬浮条已重新贴右下角（@ %d,%d）",
                      old, (w, h), x, y)

    def _screen_size(self):
        """实时屏幕分辨率（宽, 高）：优先 GetSystemMetrics（无缓存，RDP 切分辨率立即反映），回退 tkinter。

        返回 None = 两者都取不到（悬浮条保持原位不动作）。
        """
        try:
            import ctypes
            user32 = ctypes.windll.user32
            return int(user32.GetSystemMetrics(0)), int(user32.GetSystemMetrics(1))
        except Exception:
            root = self._root
            if root is None:
                return None
            try:
                return root.winfo_screenwidth(), root.winfo_screenheight()
            except Exception:
                return None

    def _refresh(self) -> None:
        if self._label is None:
            return
        if self.state.ui_pause_requested:
            self._label.config(text="恢复", bg=self.COLOR_PAUSED)
        else:
            self._label.config(text="暂停", bg=self.COLOR_RUNNING)
