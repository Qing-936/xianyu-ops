"""Windows 窗口定位 / 截图 / 鼠标键盘模拟（POC win_ui.py 演进，03 号文档 §5.1）。

- 进程定位：psutil 按进程名含「闲管家」找 PID → EnumWindows 找该 PID 的可见顶层窗口
  （多窗口取标题最长者，POC 已验证）。
- DPI 感知：导入即 SetProcessDpiAwareness(2)（POC 已验证，坐标换算前提）。
- 截图：mss 抓窗口矩形；grab_window/grab_area/grab_abs 返回内存 BGR 数组（生产 OCR 路径，
  零磁盘 IO），capture_* 才落盘（仅失败留档/验证截图）。mss 实例按线程本地复用。
- 点击/粘贴：SetCursorPos + mouse_event；中文输入一律剪贴板 + Ctrl+V
  （POC 结论：IME 逐字输入不稳）。
"""
from __future__ import annotations

import ctypes
import threading
import time
from pathlib import Path

import pywinauto
import win32api
import win32con
import win32gui
import win32process

# DPI 感知：进程启动即设，坐标换算前提（POC 已验证）
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

_TL = threading.local()


def find_window(title_part: str) -> int | None:
    """返回第一个标题含 title_part 的可见顶层窗口句柄。"""

    def cb(hwnd, found):
        if win32gui.IsWindowVisible(hwnd):
            t = win32gui.GetWindowText(hwnd)
            if title_part and title_part in t:
                found.append(hwnd)
        return True

    found: list[int] = []
    win32gui.EnumWindows(cb, found)
    return found[0] if found else None


def find_window_pid(pid: int) -> int | None:
    """返回 pid 拥有的第一个可见顶层窗口（多窗口取标题最长者，POC 已验证）。"""
    found: list[int] = []

    def cb(hwnd, _):
        if win32gui.IsWindowVisible(hwnd):
            _, wpid = win32process.GetWindowThreadProcessId(hwnd)
            if wpid == pid:
                found.append(hwnd)
        return True

    win32gui.EnumWindows(cb, None)
    if not found:
        return None
    return max(found, key=lambda h: len(win32gui.GetWindowText(h)))


def find_window_by_proc_name(proc_name: str) -> tuple[int | None, int | None]:
    """按进程名关键字（如「闲管家」）定位主窗口，返回 (hwnd, pid)。

    防御：进程迭代期间目标可能已死，psutil 对已死进程的 info 可能为 None/空 dict
    （锁屏/解锁时系统进程抖动实测触发），必须容错。
    """
    import psutil

    for p in psutil.process_iter(["name"]):
        try:
            info = p.info or {}
            if proc_name not in (info.get("name") or ""):
                continue
            hwnd = find_window_pid(p.pid)
            if hwnd:
                return hwnd, p.pid
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    return None, None


def is_window_alive(hwnd: int) -> bool:
    return bool(hwnd) and win32gui.IsWindow(hwnd)


def rect_of(hwnd: int) -> tuple[int, int, int, int]:
    return win32gui.GetWindowRect(hwnd)


def set_foreground(hwnd: int) -> bool:
    """把窗口带到前台并校验成功（截图/点击前提）。

    mss 按屏幕坐标截图，窗口被遮挡会截到别的窗口内容（实测抓到其他程序画面，
    并导致 nav_missing 误判）；SetForegroundWindow 受前台锁定策略限制可能失败，
    用 Alt 键解锁 trick + 置顶兜底 + 校验重试。
    """
    if win32gui.IsIconic(hwnd):
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
    for _ in range(5):
        if win32gui.GetForegroundWindow() == hwnd:
            time.sleep(0.15)
            return True
        try:
            win32gui.SetForegroundWindow(hwnd)
        except Exception:
            # 前台锁定：模拟 Alt 键解锁后重试（Windows 经典 trick）
            try:
                win32api.keybd_event(win32con.VK_MENU, 0, 0, 0)
                win32api.keybd_event(win32con.VK_MENU, 0, win32con.KEYEVENTF_KEYUP, 0)
                win32gui.SetForegroundWindow(hwnd)
            except Exception:
                pass
        time.sleep(0.3)
    ok = win32gui.GetForegroundWindow() == hwnd
    if not ok:
        # 兜底：临时置顶再取消置顶
        try:
            flags = win32con.SWP_NOMOVE | win32con.SWP_NOSIZE
            win32gui.SetWindowPos(hwnd, win32con.HWND_TOPMOST, 0, 0, 0, 0, flags)
            win32gui.SetWindowPos(hwnd, win32con.HWND_NOTOPMOST, 0, 0, 0, 0, flags)
            win32gui.SetForegroundWindow(hwnd)
            ok = win32gui.GetForegroundWindow() == hwnd
        except Exception:
            pass
    time.sleep(0.2)
    return ok


def capture_window(hwnd: int, path: str | Path) -> Path:
    """抓窗口矩形截图到 path，返回文件路径。"""
    return _save(grab_window(hwnd), path)


def grab_window(hwnd: int):
    """抓窗口矩形 → BGR ndarray（内存路径，不落盘；RapidOCR 直读）。"""
    left, top, right, bottom = rect_of(hwnd)
    return grab_abs(left, top, right, bottom)


def grab_area(hwnd: int, wx0: int, wy0: int, wx1: int, wy1: int):
    """抓窗口内相对矩形 (wx0,wy0)-(wx1,wy1) → BGR ndarray（区域 OCR 用，不落盘）。"""
    left, top, _, _ = rect_of(hwnd)
    return grab_abs(left + wx0, top + wy0, left + wx1, top + wy1)


def grab_abs(x0: int, y0: int, x1: int, y1: int):
    """抓屏幕绝对矩形 → BGR ndarray (h, w, 3)。

    mss 的 bgra 缓冲是 width*height*4 无行填充，直接 reshape 后丢 alpha 即 BGR；
    必须保 BGR 通道序——RapidOCR/OpenCV 约定传 RGB 会得到不同识别结果（实测不等价）。
    """
    import numpy as np

    shot = _sct().grab({"left": int(x0), "top": int(y0),
                        "width": int(x1 - x0), "height": int(y1 - y0)})
    arr = np.frombuffer(shot.bgra, dtype=np.uint8).reshape((shot.height, shot.width, 4))
    return np.ascontiguousarray(arr[:, :, :3])


def _sct():
    """线程本地复用 mss 实例（mss 非线程安全，且每次新建实例都有额外开销）。"""
    import mss

    sct = getattr(_TL, "sct", None)
    if sct is None:
        sct = mss.mss()
        _TL.sct = sct
    return sct


def _save(img, path: str | Path) -> Path:
    """BGR ndarray 落盘（与内存路径共用同一次通道换算，保证两者像素一致）。"""
    import numpy as np
    from PIL import Image

    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.ascontiguousarray(img[:, :, ::-1])).save(path)
    return path


def save_image(img, path: str | Path) -> Path:
    """把已抓好的 BGR ndarray 落盘（避免二次抓屏拍到另一时刻的画面）。"""
    return _save(img, path)


def capture_region(hwnd: int, wx0: int, wy0: int, wx1: int, wy1: int, path: str | Path) -> Path:
    """抓窗口内相对坐标 (wx0,wy0)-(wx1,wy1) 区域截图（03 号文档区域裁剪用）。"""
    return _save(grab_area(hwnd, wx0, wy0, wx1, wy1), path)


def capture_abs_region(x0: int, y0: int, x1: int, y1: int, path: str | Path) -> Path:
    return _save(grab_abs(x0, y0, x1, y1), path)


def move_click_abs(x: int, y: int, pause: float = 0.3) -> None:
    win32api.SetCursorPos((int(x), int(y)))
    time.sleep(pause)
    win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
    time.sleep(0.06)
    win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
    time.sleep(pause)


def click_window_xy(hwnd: int, wx: int, wy: int, pause: float = 0.3) -> None:
    """点击窗口内相对坐标 (wx, wy)。"""
    left, top, _, _ = rect_of(hwnd)
    move_click_abs(left + wx, top + wy, pause=pause)


def paste_clipboard(text: str) -> None:
    """剪贴板写入 + Ctrl+V（03 §5.1：中文输入一律剪贴板粘贴，IME 逐字输入不稳）。"""
    import win32clipboard

    win32clipboard.OpenClipboard()
    try:
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardData(win32clipboard.CF_UNICODETEXT, text)
    finally:
        win32clipboard.CloseClipboard()
    time.sleep(0.2)
    pywinauto.keyboard.send_keys("^v", pause=0.05)
    time.sleep(0.3)


def clear_focused_input() -> None:
    """清空当前聚焦的输入框（Ctrl+A → Delete）。

    发送前必须先清空：实测闲管家输入框会残留上一任务/上次粘贴的内容，直接粘贴会将
    残留文本与新内容拼接（残留「123」→「123您好，有货的，可以直接下单哦」）。
    """
    pywinauto.keyboard.send_keys("^a", pause=0.05)
    time.sleep(0.15)
    pywinauto.keyboard.send_keys("{DELETE}", pause=0.05)
    time.sleep(0.15)


def send_text(text: str, pause: float = 0.02) -> None:
    """逐字输入（仅 ASCII 调试用；中文请用 paste_clipboard）。"""
    for ch in text:
        pywinauto.keyboard.send_keys(ch, pause=0.0)
        time.sleep(pause)


def press_key(key: str) -> None:
    pywinauto.keyboard.send_keys("{" + key + "}", pause=0.05)
    time.sleep(0.25)
