"""消息通道实现包（03 号文档 §4）。

- `ui_ocr.py`：当前实现（UIA 优先 + OCR 兜底），config `channel.impl: ui_ocr`。
- 未来官方消息 API 落地 → 新增 `official_api.py`，替换 config 的 `channel.impl`。
"""
