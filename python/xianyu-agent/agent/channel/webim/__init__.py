"""webim 浏览器 DOM 通道子包（10 号文档 M1 基础设施移植）。

- cdp.py：零依赖 CDP 客户端（WebSocket + 调试端口）
- browser.py：Browser / AccountPage / DOM JS（stage0 增强：消息行级事件流 + 会话行级结构化）
- store.py：闲鱼号 + Cookie 供应链（accounts.json 主源 + 调试口 9401 降级导出）

WebimChannel（Channel 接口实现）在 M2 接入（agent/channel/webim_channel.py）。
"""
