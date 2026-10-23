"""本地审计 JSON Lines（03 号文档 §7）。

与服务端 app_xianyu_agent_event 互补：本地存明细（截图路径/OCR 置信度/坐标），
服务端存聚合（事件类型/级别）。ts 为本地无时区 ISO-8601（与 Java DATETIME 风格统一）。

隐私注意：审计含买家昵称与消息内容，文件权限仅本机用户可读，禁止外传。
"""
from __future__ import annotations

import json
import time
from datetime import datetime
from pathlib import Path


class AuditLogger:
    """JSON Lines 审计器：每行一条事件，按天滚动文件。"""

    def __init__(self, logs_dir: Path, retention_days: int = 30, enable: bool = True):
        self.logs_dir = Path(logs_dir)
        self.retention_days = retention_days
        self.enable = enable
        self.logs_dir.mkdir(parents=True, exist_ok=True)

    def _today_file(self) -> Path:
        return self.logs_dir / f"audit-{datetime.now():%Y%m%d}.jsonl"

    def event(self, type_: str, **detail) -> None:
        """写一条审计事件；detail 中的键值直接平铺进 JSON 行。"""
        if not self.enable:
            return
        row = {"ts": datetime.now().isoformat(timespec="seconds"), "type": type_, **detail}
        try:
            with self._today_file().open("a", encoding="utf-8") as f:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        except OSError:
            pass  # 审计失败不影响主流程

    def cleanup(self) -> int:
        """滚动清理超过 retention_days 的审计文件，返回删除数量。"""
        deleted = 0
        cutoff = time.time() - self.retention_days * 86400
        for p in self.logs_dir.glob("audit-*.jsonl"):
            try:
                if p.stat().st_mtime < cutoff:
                    p.unlink()
                    deleted += 1
            except OSError:
                pass
        return deleted
