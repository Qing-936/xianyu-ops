"""Java 交互客户端（03 号文档 §3，契约以 02 号文档 §4 /agent-api 为准）。

通用约定（03 §3.1）：
- 所有请求带 `X-Agent-Token`；显式超时 `http.timeout_sec`（默认 40，必须 > 长轮询挂起 25s）。
- 统一响应判定：`code == 0` 成功返回 data；401 → TokenInvalidError（配置错误，调用方应退出进程，
  03 决策 D-03-6）；其余业务错误码 → ApiError；5xx/网络异常 → requests.RequestException 上抛，
  由调用方按接口重试策略处理。

实施节奏：M3 步骤 3 只实现心跳（验收 = 管理端出现 Agent）；upload/poll/result/event/截图上传
随步骤 4-7 逐个补方法（03 §9.1），避免未验证代码堆积。
"""
from __future__ import annotations

import uuid
from pathlib import Path

import requests

from . import __version__


class ApiError(Exception):
    """Java 返回业务错误（code != 0，401 除外）。"""

    def __init__(self, code: int, msg: str):
        super().__init__(f"[{code}] {msg}")
        self.code = code
        self.msg = msg


class TokenInvalidError(ApiError):
    """401：Agent Token 无效。重试无意义，心跳线程收到即退出进程（03 §6 异常 7）。"""


def load_or_create_machine_code(path: str | Path) -> str:
    """机器指纹：首次生成 UUID4 并持久化（03 决策 D-03-2）。

    删除 .machine_code 文件会生成新指纹 → Java 侧注册出新 Agent，运维须知。
    """
    p = Path(path)
    if p.exists():
        code = p.read_text(encoding="utf-8").strip()
        if code:
            return code
    code = str(uuid.uuid4())
    try:
        p.write_text(code + "\n", encoding="utf-8")
    except OSError:
        pass  # 持久化失败不阻断（本次会话指纹仍稳定，仅重启后可能换新）
    return code


def load_agent_token(path: str | Path) -> str | None:
    """读取专属 Token（.agent_token，M5 一机一 Token）。

    存在且非空 → 返回专属 Token；否则 None（回退 config 的一次性注册码走首次注册）。
    """
    p = Path(path)
    if p.exists():
        token = p.read_text(encoding="utf-8").strip()
        if token:
            return token
    return None


def save_agent_token(path: str | Path, token: str) -> None:
    """持久化专属 Token（首次注册/存量补发/管理端重置后一次性下发）。

    写入失败不阻断（仅本次会话内存中仍可用，重启后需重新激活）。
    """
    try:
        Path(path).write_text(token + "\n", encoding="utf-8")
    except OSError:
        pass


class ApiClient:
    """Java /agent-api 交互客户端（Session 复用连接）。"""

    def __init__(self, base_url: str, token: str, timeout_sec: float = 40):
        self.base_url = base_url.rstrip("/")
        self.timeout_sec = timeout_sec
        self._session = requests.Session()
        self._session.headers["X-Agent-Token"] = token

    def set_token(self, token: str) -> None:
        """切换请求 Token（M5：首次心跳收到专属 Token 后调用，Session 连接池复用不变）。"""
        self._session.headers["X-Agent-Token"] = token

    # ------------------------------------------------------------------ 通用
    def _post(self, path: str, json: dict | None = None, timeout: float | None = None) -> dict:
        return self._request("POST", path, json=json, timeout=timeout)

    def _get(self, path: str, timeout: float | None = None) -> dict:
        return self._request("GET", path, timeout=timeout)

    def _request(self, method: str, path: str, json: dict | None = None,
                 files: dict | None = None, timeout: float | None = None) -> dict:
        resp = self._session.request(
            method,
            self.base_url + path,
            json=json,
            files=files,
            timeout=timeout or self.timeout_sec,
        )
        resp.raise_for_status()  # 5xx → HTTPError（调用方按网络类异常处理）
        body = resp.json()
        code = body.get("code", -1)
        if code == 0:
            return body.get("data") or {}
        if code == 401:
            raise TokenInvalidError(code, body.get("msg", "Agent Token 无效"))
        raise ApiError(code, body.get("msg", "未知错误"))

    # ------------------------------------------------------------------ 心跳
    def heartbeat(self, machine_code: str, host_name: str, os_version: str,
                  screen_locked: bool, accounts: list,
                  metrics: dict | None = None,
                  capabilities: list | None = None) -> dict:
        """POST /agent-api/xianyu/agent/heartbeat（02 §4.1）。

        返回 data：{ agentId, serverTime, pollIntervalSec, pendingTaskCount, agentToken? }；
        agentToken 仅在首次注册/存量补发/管理端重置后一次性下发（M5 一机一 Token），
        调用方收到后须立即持久化到 .agent_token 并切换后续请求 Token；
        心跳异常按 §3.1 处理：401 退出进程，其余告警下轮重试（调用方决定）。
        """
        req = {
            "machineCode": machine_code,
            "hostName": host_name,
            "osVersion": os_version,
            "agentVersion": __version__,
            "screenLocked": bool(screen_locked),
            "accounts": accounts or [],
            "capabilities": capabilities or ["text"],
        }
        if metrics:
            req["metrics"] = metrics
        return self._post("/agent-api/xianyu/agent/heartbeat", req)

    # ------------------------------------------------------------------ 消息上报
    def upload(self, messages: list[dict]) -> dict:
        """POST /agent-api/xianyu/message/upload（02 §4.2，M3 步骤 4）。

        messages 每项字段（与 Java UploadReqVO.MessageItem 对齐）：
        { clientMsgId, accountKey:{userNick, authorizeId}, buyerId, buyerNick,
          content, contentType, direction, receiveTime,
          chatId?, itemId?(string，13 号 §4.1), ext?(商品卡快照，键名契约 13 号 §4.2) }
        返回 data：{ acceptedCount, duplicateCount, failedCount, failures }。
        """
        return self._post("/agent-api/xianyu/message/upload", {"messages": messages})

    # ------------------------------------------------------------------ 任务长轮询
    def poll_tasks(self) -> list[dict]:
        """GET /agent-api/xianyu/reply-task/poll（02 §4.3，M3 步骤 5）。

        阻塞 ≤ Java 长轮询挂起时长（25s，http.timeout_sec 40s 覆盖）；
        返回领取到的任务列表（无任务 = 空数组），每项：
        { taskId, accountKey:{userNick, authorizeId}, buyerNick, content, source,
          itemId?, knownItems? }（itemId 为 string 可空，13 号 §4.3，随任务流到 send_message 定位；
          knownItems 为该买家×账号会话库中出现过的商品 id string 数组，13 号 §五 增强 B，供弱校验兜底放行）。
        """
        data = self._get("/agent-api/xianyu/reply-task/poll")
        return data.get("tasks") or []

    # ------------------------------------------------------------------ 结果回传
    def report_result(self, task_id: int, status: str, result_msg: str = "",
                      verify_image_url: str = "") -> dict:
        """POST /agent-api/xianyu/reply-task/{taskId}/result（02 §4.4，M3 步骤 5）。

        status 仅 success / failed；send_unknown 场景不回传结果、走 report_event
        （02 §4.5：任务停留 dispatched，Java 超时兜底）。
        """
        req: dict = {"status": status}
        if result_msg:
            req["resultMsg"] = result_msg
        if verify_image_url:
            req["verifyImageUrl"] = verify_image_url
        return self._post(f"/agent-api/xianyu/reply-task/{task_id}/result", req)

    # ------------------------------------------------------------------ 验证截图上传
    def upload_file(self, file_path: str | Path, tag: str = "verify") -> str:
        """POST /agent-api/xianyu/file/upload（02 §4.6，M3 步骤 6）。

        multipart 单文件（file 字段），返回 OSS URL 字符串；
        上传失败上抛（调用方重试 1 次后以空 URL 回传 result）。
        """
        p = Path(file_path)
        with p.open("rb") as f:
            data = self._request(
                "POST", "/agent-api/xianyu/file/upload",
                files={"file": (f"{tag}-{p.name}", f, "image/png")},
            )
        # Java file/upload 返回 CommonResult<String>，data 直接是 OSS URL 字符串
        # （非 {url:...} 对象；不兼容会导致 "'str' object has no attribute 'get'"）
        if isinstance(data, str):
            return data
        return str(data.get("url") or "")

    # ------------------------------------------------------------------ 事件上报
    def report_event(self, account_key: dict | None, event_type: str,
                     level: str = "info", content: str = "",
                     raw_json: dict | None = None) -> dict:
        """POST /agent-api/xianyu/event（02 §4.5，M3 步骤 7 接入，步骤 4-5 已用）。

        与 EventReqVO 对齐：accountKey{userNick, authorizeId}/eventType/level/content/rawJson。
        高频事件节流（同类 60s 最多 1 次）由调用方负责（03 §3.7）。
        """
        req: dict = {"eventType": event_type, "level": level}
        if account_key:
            req["accountKey"] = account_key
        if content:
            req["content"] = content
        if raw_json:
            req["rawJson"] = raw_json
        return self._post("/agent-api/xianyu/event", req)
