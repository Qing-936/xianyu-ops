"""闲管家聊天区时间文本 → 时间戳估算（03 号文档 §5.3）。

| OCR 读到 | 估算 |
|---------|------|
| `刚刚` | now |
| `x 分钟前` | now - x min |
| `HH:mm` | 今天 HH:mm；若估算 > now → 视为昨天 HH:mm |
| `昨天 HH:mm` / 日期 | 按文案解析（少见，尽力解析） |

估算不确定性由 Java 窗口判重消化（01 §3.5），Agent 无需精确到秒。
调用方在输出前加 SharedState.clock_offset_sec 时钟校正（03 §3.1）。
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta

# 「2026-09-09」「9月9日」「09-09」等日期前缀 + 可选时间
_DATE_TIME_RE = re.compile(
    r"(?:(\d{4})[-/年])?(\d{1,2})[-/月](\d{1,2})日?"
    r"(?:\s*(\d{1,2}):(\d{2}))?"
)
# 单独 HH:mm（或 H:mm）
_HHMM_RE = re.compile(r"(?<!\d)(\d{1,2}):(\d{2})(?!\d)")
_MINUTES_AGO_RE = re.compile(r"(\d+)\s*分钟前")
_HOURS_AGO_RE = re.compile(r"(\d+)\s*小时前")
_DAYS_AGO_RE = re.compile(r"(\d+)\s*天前")


def parse(text: str, now: datetime | None = None) -> datetime | None:
    """解析时间文案，返回估算 datetime（无时区）；无法解析返回 None。

    now 仅用于测试注入；缺省取当前本地时间。
    """
    if not text:
        return None
    now = now or datetime.now()
    text = text.strip()

    # 「刚刚」
    if "刚刚" in text:
        return now

    # 「x 分钟前」
    m = _MINUTES_AGO_RE.search(text)
    if m:
        return now - timedelta(minutes=int(m.group(1)))

    # 「x 小时前」/「x 天前」（07 号文档 T1.1：会话行相对时间纳入变化检测所需）
    m = _HOURS_AGO_RE.search(text)
    if m:
        return now - timedelta(hours=int(m.group(1)))
    m = _DAYS_AGO_RE.search(text)
    if m:
        return now - timedelta(days=int(m.group(1)))

    # 「昨天 HH:mm」（可能带空格）
    if "昨天" in text:
        m = _HHMM_RE.search(text)
        if m:
            base = now.replace(hour=int(m.group(1)), minute=int(m.group(2)),
                               second=0, microsecond=0) - timedelta(days=1)
            return base
        # 「昨天」无时间 → 昨天 0 点（信息太少，尽力）
        return (now - timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)

    # 带日期前缀：2026-09-09 10:30 / 9月9日 10:30
    m = _DATE_TIME_RE.search(text)
    if m and m.group(2) and m.group(3):
        month, day = int(m.group(2)), int(m.group(3))
        year = int(m.group(1)) if m.group(1) else now.year
        hour = int(m.group(4)) if m.group(4) else 0
        minute = int(m.group(5)) if m.group(5) else 0
        try:
            return datetime(year, month, day, hour, minute)
        except ValueError:
            return None

    # 单独 HH:mm → 今天；若估算 > now → 视为昨天
    m = _HHMM_RE.search(text)
    if m:
        est = now.replace(hour=int(m.group(1)), minute=int(m.group(2)),
                          second=0, microsecond=0)
        if est > now:
            est -= timedelta(days=1)
        return est

    return None
