"""通道工厂：按 config `channel.impl` 实例化消息通道（10 号文档 M0）。

- `ui_ocr`：UIA 优先 + OCR 兜底通道（当前生产实现）。
- `webim`：浏览器 DOM 通道（阶段 1 M2 已接入；M3 灰度切）。
- 未知 impl 阻断启动（与 main.check_config 的 P3-2 同逻辑：配置错误运行无意义）。
"""
from __future__ import annotations

from .base import Channel


def create_channel(cfg: dict, log, audit, shots_dir) -> Channel:
    impl = str((cfg.get("channel") or {}).get("impl", "ui_ocr") or "ui_ocr")
    if impl == "ui_ocr":
        from agent.channel.ui_ocr import UiOcrChannel
        return UiOcrChannel(cfg, log, audit, shots_dir)
    if impl == "webim":
        from agent.channel.webim_channel import WebimChannel
        return WebimChannel(cfg, log, audit, shots_dir)
    raise SystemExit(f"[config-error] 未知 channel.impl: {impl}（可选: ui_ocr / webim）")
