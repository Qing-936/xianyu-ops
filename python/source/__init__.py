"""
货源管理模块 — 对接 Java 任务系统，执行闲鱼数据采集

本模块完全自包含，无需依赖项目根目录下的其他模块即可独立运行。

目录结构：
  source/
  ├── config.py              # 独立配置（MySQL、API、Headers 等）
  ├── goofish_utils.py       # 闲鱼签名/Cookie/登录工具
  ├── goofish_search_api.py  # 闲鱼搜索 & 详情 API 封装
  ├── service.py             # 业务服务层（采集流程）
  ├── route.py               # FastAPI 路由
  ├── server.py              # 独立启动入口
  ├── cookie.json            # Cookie 数据
  ├── static/                # 签名 JS
  │   └── goofish_js_version_2.js
  ├── gen_tfstk.js           # tfstk 生成脚本
  ├── sql/                   # DDL
  │   └── app_source.sql
  └── test/                  # 测试脚本

独立启动：
  cd <项目根目录>
  python -m uvicorn source.server:app --port 8002 --reload
  # 或
  python source/server.py
"""
