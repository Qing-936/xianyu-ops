"""零依赖 Chrome DevTools Protocol 客户端（仅使用 Python 标准库）。

用途：连接浏览器（Edge/Chrome）或 Electron 应用的本地调试端口，
执行 CDP 命令、在页面里执行 JS、以及为多账号创建隔离的 browser context。

移植自 reference-projects/xianyu-webim/src/cdp_client.py（10 号文档 M1），
仅改 docstring 引用，逻辑零改动。

设计要点：
- 自己实现最小 WebSocket 客户端（握手 + 文本帧 + ping/pong），不依赖 websocket-client；
- 同时支持「页面级 WS」（/json/list 里的 webSocketDebuggerUrl）与
  「浏览器级 WS + 会话」（Target.attachToTarget(flatten=True)）两种调用方式。
"""
from __future__ import annotations

import base64
import json
import os
import socket
import struct
import time
import urllib.request


# --------------------------------------------------------------------------- #
# HTTP 帮助函数
# --------------------------------------------------------------------------- #
def http_json(url: str, timeout: float = 8.0):
    """GET 一个 JSON 接口（用于 /json/version、/json/list）。"""
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


def wait_port(port: int, host: str = "127.0.0.1", timeout: float = 90.0) -> bool:
    """等待 TCP 端口可连接（浏览器启动需要一点时间）。"""
    end = time.time() + timeout
    while time.time() < end:
        sock = socket.socket()
        sock.settimeout(1)
        try:
            if sock.connect_ex((host, port)) == 0:
                return True
        finally:
            sock.close()
        time.sleep(1)
    return False


def list_targets(port: int):
    """返回调试端口上的所有目标（页面 / webview / iframe）。"""
    return http_json(f"http://127.0.0.1:{port}/json/list")


def pick_target(targets, needle: str):
    """按 URL 子串挑选目标；needle 为 '*' 时返回第一个。"""
    if needle == "*":
        return targets[0]
    for target in targets:
        if needle in (target.get("url") or ""):
            return target
    for target in targets:
        if needle in (target.get("title") or ""):
            return target
    raise RuntimeError(f"target not found: {needle}")


# --------------------------------------------------------------------------- #
# 最小 WebSocket 客户端
# --------------------------------------------------------------------------- #
class WebSocket:
    """仅实现 CDP 需要的最小 WebSocket 客户端（文本帧 + ping/pong）。"""

    def __init__(self, url: str, timeout: float = 60.0):
        if not url.startswith("ws://"):
            raise ValueError("only ws:// is supported")
        hostport, _, path = url[5:].partition("/")
        path = "/" + path
        host, _, port = hostport.partition(":")
        port = int(port or 80)
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {hostport}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode())
        buffer = b""
        while b"\r\n\r\n" not in buffer:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("connection closed during handshake")
            buffer += chunk
        head, _, rest = buffer.partition(b"\r\n\r\n")
        status = head.split(b"\r\n")[0]
        if b" 101 " not in status:
            raise RuntimeError("websocket handshake failed: " + status.decode("latin1"))
        self._buffer = rest

    def _read(self, size: int) -> bytes:
        while len(self._buffer) < size:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("connection closed")
            self._buffer += chunk
        data, self._buffer = self._buffer[:size], self._buffer[size:]
        return data

    def send_text(self, text: str) -> None:
        payload = text.encode()
        length = len(payload)
        mask = os.urandom(4)
        header = bytearray([0x81])
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", length)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv_text(self) -> str:
        data = b""
        while True:
            head = self._read(2)
            fin = head[0] & 0x80
            opcode = head[0] & 0x0F
            masked = head[1] & 0x80
            length = head[1] & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if masked else None
            payload = self._read(length)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x9:  # ping
                continue
            if opcode == 0x8:  # close
                raise RuntimeError("closed by peer")
            data += payload
            if fin:
                break
        return data.decode("utf-8", "replace")

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# --------------------------------------------------------------------------- #
# CDP 客户端
# --------------------------------------------------------------------------- #
class CDP:
    """CDP 调用封装：既支持页面级连接，也支持浏览器级 + 会话。

    所有调用带墙钟 deadline 超时（默认 90s，可逐调用覆盖）：浏览器事件流会让
    socket 层「有数据」从而永不触发 socket.timeout，仅靠 socket 超时无法打断
    卡死的调用（2026-09-22 vm101 离线根因：OPEN_JS Promise 不 resolve → evaluate
    永久阻塞 328s → 主循环无 touch → 看门狗杀进程）。超时抛 TimeoutError，由
    上层转 ChannelError 走自愈路径。
    """

    def __init__(self, ws_url: str, timeout: float = 60.0):
        self.ws = WebSocket(ws_url, timeout=timeout)
        self.call_timeout = 90.0  # 单次调用上限（正常 OPEN_JS 40 步滚动 <40s，不误伤）
        self._id = 0

    def _wait_response(self, message: dict, method: str, timeout: float | None) -> dict:
        """发送命令并循环收帧直到 id 匹配；deadline 超时抛 TimeoutError。

        迟到响应（超时后才到）会被后续调用的 id 匹配循环自然丢弃，帧流不乱序。
        """
        self._id += 1
        message = dict(message)
        message["id"] = self._id
        self.ws.send_text(json.dumps(message))
        limit = timeout if timeout is not None else self.call_timeout
        deadline = time.time() + limit
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError(f"CDP {method} 无响应超时（>{limit:.0f}s）")
            # 事件流多时每帧都会重查 deadline；事件流少时靠 5s 粒度 socket 超时兜底
            self.ws.sock.settimeout(min(remaining, 5.0))
            try:
                data = json.loads(self.ws.recv_text())
            except socket.timeout:
                continue
            if data.get("id") == self._id:
                return data

    def call(self, method: str, params: dict | None = None, timeout: float | None = None) -> dict:
        """浏览器级 / 页面级直接调用。"""
        return self._wait_response({"method": method, "params": params or {}}, method, timeout)

    def call_session(self, session_id: str, method: str, params: dict | None = None,
                     timeout: float | None = None) -> dict:
        """在 attachToTarget 得到的会话里调用（webview / 新建 target 都用这个）。"""
        message = {"method": method, "params": params or {}, "sessionId": session_id}
        return self._wait_response(message, method, timeout)

    def evaluate(self, expression: str, session_id: str | None = None, await_promise: bool = True,
                 timeout: float | None = None):
        """执行 JS 并返回结果值。"""
        params = {"expression": expression, "returnByValue": True,
                  "awaitPromise": await_promise, "userGesture": True}
        result = self.call_session(session_id, "Runtime.evaluate", params, timeout=timeout) if session_id \
            else self.call("Runtime.evaluate", params, timeout=timeout)
        payload = result.get("result", {})
        if payload.get("exceptionDetails"):
            raise RuntimeError("JS exception: " +
                               str(payload["exceptionDetails"].get("exception", {}).get("description"))[:300])
        return payload.get("result", {}).get("value")

    def evaluate_handle(self, expression: str, session_id: str | None = None,
                        await_promise: bool = True, timeout: float | None = None) -> str | None:
        """执行 JS 并返回 RemoteObject.objectId（returnByValue=false）。

        用于 DOM.setFileInputFiles 等必须传 objectId 的 CDP 命令（15 号文档图片外发）。
        """
        params = {"expression": expression, "returnByValue": False,
                  "awaitPromise": await_promise, "userGesture": True}
        result = self.call_session(session_id, "Runtime.evaluate", params, timeout=timeout) if session_id \
            else self.call("Runtime.evaluate", params, timeout=timeout)
        payload = result.get("result", {})
        if payload.get("exceptionDetails"):
            raise RuntimeError("JS exception: " +
                               str(payload["exceptionDetails"].get("exception", {}).get("description"))[:300])
        return payload.get("result", {}).get("objectId")

    def close(self) -> None:
        self.ws.close()


# --------------------------------------------------------------------------- #
# 浏览器可执行文件
# --------------------------------------------------------------------------- #
WINDOWS_BROWSERS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
]
POSIX_BROWSERS = [
    "/usr/bin/microsoft-edge",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
]


def find_browser() -> str | None:
    """返回本机可用的 Edge/Chrome 可执行文件路径。"""
    for path in WINDOWS_BROWSERS + POSIX_BROWSERS:
        if os.path.exists(path):
            return path
    return None
