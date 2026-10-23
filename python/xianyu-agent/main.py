"""闲鱼机器 Agent 入口（03 号文档 §1.2）。

用法：
    python main.py --check-config   # 配置校验（M3 步骤 1 验收）
    python main.py --smoke          # 冒烟：定位窗口 → 截图 → OCR 读屏（M3 步骤 2 验收）
    python main.py                  # 正式运行（心跳 + 守护 + 主循环 + 任务长轮询 四线程）
"""
from __future__ import annotations

import argparse
import logging
import os
import platform
import socket
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

import requests
import yaml

from agent.api_client import (ApiClient, ApiError, TokenInvalidError,
                              load_or_create_machine_code, load_agent_token, save_agent_token)
from agent.audit import AuditLogger
from agent.control import FloatingControl
from agent.guard import Guard
from agent.shared_state import SharedState
from agent.state_machine import StateMachine

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.yaml"
LOGS_DIR = BASE_DIR / "logs"


def load_config(path: Path = CONFIG_PATH) -> dict:
    if not path.exists():
        raise SystemExit(f"配置文件不存在: {path}")
    with path.open(encoding="utf-8") as f:
        cfg = yaml.safe_load(f)
    if not isinstance(cfg, dict):
        raise SystemExit("配置文件格式错误：顶层必须为 YAML 映射")
    # 本地覆盖（M3 评审 P2-2）：config.local.yaml 存在时 deep-merge 覆盖（放一次性注册码等敏感值，
    # 已被 .gitignore 排除不入库）；用法见 config.yaml 注释
    local_path = path.with_name(path.stem + ".local" + path.suffix)
    if local_path.exists():
        with local_path.open(encoding="utf-8") as f:
            local_cfg = yaml.safe_load(f)
        if not isinstance(local_cfg, dict):
            raise SystemExit(f"本地覆盖配置格式错误（顶层必须为 YAML 映射）: {local_path}")
        cfg = _deep_merge(cfg, local_cfg)
    return cfg


def _deep_merge(base: dict, override: dict) -> dict:
    """递归合并：override 覆盖 base；嵌套 dict 逐层合并，其余类型整体替换。"""
    merged = dict(base)
    for key, value in override.items():
        if key in merged and isinstance(merged[key], dict) and isinstance(value, dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def check_config(cfg: dict) -> list[str]:
    """返回错误列表；空列表 = 通过。"""
    errors: list[str] = []

    def req(section: str, key: str):
        if not cfg.get(section) or cfg[section].get(key) in (None, ""):
            errors.append(f"[{section}.{key}] 缺失")

    req("server", "base_url")
    # 凭据二选一：已落盘 .agent_token（已激活的老机器）或配置了一次性注册码（新机器首次注册）
    if not load_agent_token(BASE_DIR / ".agent_token") and not str(cfg.get("server", {}).get("enroll_code") or "").strip():
        errors.append("[server.enroll_code] 缺失且未发现 .agent_token；新机器请先到管理端"
                      "「闲鱼 → Agent 管理 → 注册码」生成一次性注册码填入 config.local.yaml")
    base_url = str(cfg.get("server", {}).get("base_url", ""))
    if base_url and not base_url.startswith(("http://", "https://")):
        errors.append(f"[server.base_url] 非法 URL: {base_url}")
    timeout = cfg.get("http", {}).get("timeout_sec", 0)
    long_poll = cfg.get("poll", {}).get("long_poll_timeout_sec", 0)
    if timeout <= long_poll:
        errors.append(f"[http.timeout_sec] {timeout}s 必须大于长轮询挂起 {long_poll}s（03 §0.1-4）")
    # 通道条件化校验（10 号文档 M1 步骤 4）：ui_ocr 要求 ui.* 区域坐标，webim 要求 channel.webim 段
    impl = str((cfg.get("channel") or {}).get("impl") or "ui_ocr")
    if impl == "ui_ocr":
        req("ui", "proc_name")
        # 区域坐标为四元组校验
        for key in ("account_tab_region", "conversation_list_region", "chat_area_region", "chat_header_region"):
            v = cfg.get("ui", {}).get(key)
            if not isinstance(v, (list, tuple)) or len(v) != 4:
                errors.append(f"[ui.{key}] 必须为 [x0, y0, x1, y1] 四元组")
    elif impl == "webim":
        w = (cfg.get("channel") or {}).get("webim")
        if not isinstance(w, dict):
            errors.append("[channel.webim] 缺失（impl=webim 需配置 channel.webim 段，见 config.yaml 注释）")
        else:
            port = w.get("port")
            if not isinstance(port, int) or not 1024 <= port <= 65535:
                errors.append("[channel.webim.port] 必须为 1024~65535 的端口")
            accounts_file = w.get("accounts_file") or ""
            if accounts_file and not Path(accounts_file).exists():
                errors.append(f"[channel.webim.accounts_file] 文件不存在: {accounts_file}")
    else:
        errors.append(f"[channel.impl] 未知: {impl}（可选 ui_ocr / webim）")
    return errors


def init_logging() -> logging.Logger:
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("xianyu-agent")
    logger.setLevel(logging.INFO)
    if not logger.handlers:
        fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
        fh = logging.FileHandler(LOGS_DIR / "agent.log", encoding="utf-8")
        fh.setFormatter(fmt)
        logger.addHandler(fh)
        sh = logging.StreamHandler()
        sh.setFormatter(fmt)
        logger.addHandler(sh)
    return logger


def smoke(cfg: dict, log: logging.Logger) -> int:
    """M3 步骤 2 冒烟：定位窗口 → 截图 → OCR 读屏，等价 POC 能力验证。"""
    from agent.ui import ocr as ocr_mod
    from agent.ui import win

    proc_name = cfg["ui"]["proc_name"]
    log.info("[smoke] 定位进程: %s", proc_name)
    hwnd, pid = win.find_window_by_proc_name(proc_name)
    if not hwnd:
        log.error("[smoke] 未找到进程名含「%s」的窗口，请确认闲管家已启动", proc_name)
        return 1
    win.set_foreground(hwnd)
    left, top, right, bottom = win.rect_of(hwnd)
    log.info("[smoke] 窗口: hwnd=%d pid=%d rect=(%d,%d,%d,%d) %dx%d",
             hwnd, pid, left, top, right, bottom, right - left, bottom - top)
    shot = win.capture_window(hwnd, LOGS_DIR / "shots" / "smoke.png")
    log.info("[smoke] 截图已存: %s", shot)
    engine = ocr_mod.Ocr(lang=cfg.get("ocr", {}).get("lang", "ch"),
                         min_confidence=cfg.get("ocr", {}).get("min_confidence", 0.85),
                         use_cls=bool(cfg.get("ocr", {}).get("use_cls", False)),
                         intra_op_num_threads=int(cfg.get("ocr", {}).get("intra_op_num_threads", 0) or 0),
                         log=log)
    # 禁用缓存（M3 评审 P2-1）：smoke 每次写同一个 smoke.png，若命中 .ocr.json 旧缓存，
    # 第二次起读到的就是第一次的过期文本，冒烟验证失真；缓存仅 POC 调试显式开启
    items = engine.run(shot, use_cache=False)
    log.info("[smoke] OCR 识别 %d 条文本（≥%.2f 置信度 %d 条）",
             len(items), engine.min_confidence, len(engine.high_conf(items)))
    for it in items[:30]:
        log.info("[smoke]   [%s] %.3f (%d,%d)-(%d,%d)",
                 it["text"][:40], it["score"], it["x0"], it["y0"], it["x1"], it["y1"])
    return 0


def _sync_clock_offset(state: SharedState, server_time: str | None, log: logging.Logger) -> None:
    """按心跳响应 serverTime 校正时钟偏移（03 §3.1）；缺失/解析失败保持原值。"""
    if not server_time:
        return
    try:
        state.clock_offset_sec = (datetime.fromisoformat(server_time) - datetime.now()).total_seconds()
    except ValueError:
        log.warning("心跳 serverTime 解析失败，保持原偏移: %s", server_time)


def _wait_first_heartbeat(state: SharedState, log: logging.Logger, timeout_sec: float = 10) -> None:
    """等待首个心跳完成（M5）：未激活专属 Token 前，除心跳外的业务请求会被 401。

    阻塞至多 timeout_sec；超时不阻断（可能离线启动，心跳恢复后 state.heartbeat_ok 转真，
    注册码/专属 Token 被拒时心跳线程会自行退出进程并留下错误日志）。
    """
    deadline = time.time() + timeout_sec
    while not state.heartbeat_ok and time.time() < deadline:
        time.sleep(0.2)
    if not state.heartbeat_ok:
        log.warning("等待首个心跳超时（%ss）继续启动；若日志持续 401 请检查 Token", timeout_sec)


def _heartbeat_loop(cfg: dict, client: ApiClient, state: SharedState, machine_code: str,
                    host_name: str, os_version: str, audit: AuditLogger, token_path: Path,
                    stop: threading.Event, log: logging.Logger) -> None:
    """心跳线程（03 §1.1 / §3.2）：启动即握手注册，随后按服务端 pollIntervalSec 周期循环。

    只发 HTTP 不碰 UI（评审 P2-3）；周期以服务端响应为准（评审 P2-1），本地配置仅作首次握手前初值。
    401 → 记日志后退出进程（03 决策 D-03-6：Token 配置错误/已失效，运行无意义）：
    常见原因：注册码已过期或已被消费（须在管理端重新生成）、克隆机未清 .agent_token、管理端刚重置 Token。
    M5：响应携带 agentToken（首次注册/存量补发/重置后一次性下发）→ 持久化 .agent_token 并切换。
    """
    import psutil  # 延迟加载：--check-config / --smoke 路径不引入

    interval = float(cfg.get("heartbeat", {}).get("interval_sec", 15))
    proc = psutil.Process()
    while not stop.is_set():
        try:
            metrics = {"cpuPercent": int(proc.cpu_percent()), "memPercent": int(proc.memory_percent())}
            resp = client.heartbeat(machine_code, host_name, os_version,
                                    state.screen_locked or state.desktop_locked,
                                    state.account_snapshot(), metrics,
                                    capabilities=state.capabilities)
            state.agent_id = resp.get("agentId")
            issued_token = resp.get("agentToken")
            if issued_token:
                # M5：服务端一次性下发专属 Token → 先落盘再切换，避免切换后写入失败丢 Token
                save_agent_token(token_path, issued_token)
                client.set_token(issued_token)
                log.info("已收到专属 Token 并启用（已持久化 %s，长度 %d）", token_path, len(issued_token))
            _sync_clock_offset(state, resp.get("serverTime"), log)
            server_interval = resp.get("pollIntervalSec")
            if server_interval:
                state.poll_interval_sec = float(server_interval)
                interval = state.poll_interval_sec
            state.last_heartbeat_at = time.time()
            state.heartbeat_ok = True
            log.info("心跳 OK: agentId=%s interval=%ss offset=%+.1fs pending=%s",
                     state.agent_id, interval, state.clock_offset_sec, resp.get("pendingTaskCount"))
            audit.event("heartbeat", agentId=state.agent_id, intervalSec=interval,
                        offsetSec=round(state.clock_offset_sec, 1))
        except TokenInvalidError as e:
            log.error("Agent Token 无效（%s）：配置错误，退出进程（03 §6 异常 7）", e.msg)
            audit.event("heartbeat_fatal", reason="token_invalid")
            os._exit(1)
        except ApiError as e:
            log.warning("心跳被拒 code=%s msg=%s（下轮重试）", e.code, e.msg)
        except requests.RequestException as e:
            log.warning("心跳请求异常: %s（下轮重试）", e)
        except Exception:
            log.exception("心跳未知异常（下轮重试）")
        stop.wait(interval)


def run_main(cfg: dict, log: logging.Logger) -> int:
    """正式运行（03 §1.1）：心跳线程 + 守护线程 + 主循环状态机（SCAN/READ/UPLOAD/EXEC/RESULT/WAIT）
    + 任务长轮询线程（POLL → 队列 → 唤醒主循环）。

    M3 步骤 3 验收：心跳注册，Java 管理端出现本机 Agent（online）。
    M3 步骤 4 验收：手机闲鱼发消息 → 30s 内 Java 消息表有记录。
    M3 步骤 5 验收：Java 创建任务 → Agent 实际发出 → 任务 success。
    M3 步骤 7 验收：锁屏 → Java 收到 screen_locked 事件；解锁恢复（guard）。
    M5 一机一 Token：优先用 .agent_token 专属 Token；缺失时用 config 的一次性注册码走首次注册，
    收到下发 Token 后自动持久化并切换（注册码随即在服务端作废）。
    """
    token_path = BASE_DIR / ".agent_token"
    agent_token = load_agent_token(token_path)
    if agent_token:
        log.info("使用专属 Token（%s）", token_path)
    else:
        log.info("未发现 .agent_token，使用一次性注册码首次注册（收到专属 Token 后自动持久化）")
    client = ApiClient(
        cfg["server"]["base_url"],
        agent_token or str(cfg["server"].get("enroll_code") or "").strip(),
        cfg.get("http", {}).get("timeout_sec", 40),
    )
    state = SharedState()
    # 15 号文档：首个心跳即带上媒体能力，避免 StateMachine 初始化前的 15s 窗口只报 text
    if (str((cfg.get("channel") or {}).get("impl") or "") == "webim"
            and bool((cfg.get("outbound") or {}).get("enabled"))):
        state.capabilities = ["text", "image", "emoji"]
    audit = AuditLogger(
        LOGS_DIR,
        retention_days=cfg.get("guard", {}).get("audit_retention_days", 30),
        enable=cfg.get("audit", {}).get("enable", True),
    )
    machine_code = load_or_create_machine_code(BASE_DIR / ".machine_code")
    host_name = socket.gethostname()
    os_version = platform.platform()
    log.info("Agent 启动: machineCode=%s host=%s", machine_code, host_name)

    stop = threading.Event()
    hb_ref: list[threading.Thread | None] = [None]

    def spawn_heartbeat() -> threading.Thread:
        """心跳线程创建入口（可重复调用：03 §6 异常 8 守护线程重启心跳用）。"""
        t = threading.Thread(
            target=_heartbeat_loop,
            args=(cfg, client, state, machine_code, host_name, os_version, audit, token_path, stop, log),
            name="heartbeat",
            daemon=True,
        )
        t.start()
        hb_ref[0] = t
        return t

    spawn_heartbeat()
    # M5：等首个心跳完成再启动守护/主循环——专属 Token 激活前，业务请求会被 401 拒绝；
    # 等待至多 10s，超时（如离线启动）不阻断
    _wait_first_heartbeat(state, log, 10)
    guard = Guard(cfg, client, state, audit, log, spawn_heartbeat, hb_ref, LOGS_DIR / "shots")
    guard_thread = threading.Thread(target=guard.run, args=(stop,), name="guard", daemon=True)
    guard_thread.start()
    audit.event("agent_start", machineCode=machine_code)

    machine = StateMachine(cfg, client, state, audit, LOGS_DIR / "shots", log)
    machine.channel.warmup()  # 03 §2.1 INIT：通道预热（ui_ocr=OCR 模型空白图；webim=浏览器启动）
    # R5 人工接管：状态机挪到后台线程，主线程留给 tkinter 悬浮控制条
    # （tkinter 只支持主线程 mainloop；状态机仍是唯一触碰 UI 自动化的线程，03 评审 P2-3 不破）
    machine_thread = threading.Thread(target=machine.run, name="state-machine", daemon=True)
    machine_thread.start()
    control = FloatingControl(cfg, state, log)
    try:
        if not control.run():
            # 悬浮条不可用/未启用：退化为旧行为（主线程等待状态机，Ctrl+C 可中断）
            machine_thread.join()
    except KeyboardInterrupt:
        log.info("收到 Ctrl+C，正在退出…")
    finally:
        machine.stop.set()
        stop.set()
        hb = hb_ref[0]
        if hb is not None:
            hb.join(timeout=5)
        guard_thread.join(timeout=5)
        machine_thread.join(timeout=5)
        audit.event("agent_stop")
        log.info("Agent 已停止")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="闲鱼机器 Agent")
    parser.add_argument("--check-config", action="store_true", help="校验 config.yaml 后退出")
    parser.add_argument("--smoke", action="store_true", help="冒烟：定位窗口/截图/OCR 后退出")
    args = parser.parse_args()

    cfg = load_config()
    errors = check_config(cfg)
    if errors:
        for e in errors:
            print(f"[config-error] {e}", file=sys.stderr)
        if args.check_config:
            return 1
        # M3 评审 P3-2：正式运行配置错误阻断启动（与 401 退出进程同逻辑，03 D-03-6：配置错误运行无意义）
        print("[config-error] 配置校验未通过，已阻断启动（修复后重试，或用 --check-config 查看明细）", file=sys.stderr)
        return 1
    # 凭据只显来源与长度，不回显明文（注册码/专属 Token 均属敏感值）
    credential = "agent_token(.agent_token)" if load_agent_token(BASE_DIR / ".agent_token") \
        else f"enroll_code(<len:{len(str(cfg['server'].get('enroll_code') or ''))}>)"
    print(f"[check-config] OK (credential={credential}, base_url={cfg['server']['base_url']})")

    log = init_logging()
    if args.check_config:
        return 0
    if args.smoke:
        return smoke(cfg, log)
    return run_main(cfg, log)


if __name__ == "__main__":
    raise SystemExit(main())
