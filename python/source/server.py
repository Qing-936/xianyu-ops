"""
货源管理模块 - 独立启动入口
可直接运行此文件启动仅包含 source 路由的 FastAPI 服务

启动方式：
  cd <项目根目录>
  python -m uvicorn source.server:app --host 0.0.0.0 --port 8002 --reload

或者直接：
  python source/server.py
"""

import sys
import os
import logging
from contextlib import asynccontextmanager

# 确保 source/ 的父目录在 sys.path 中（支持 python server.py 和 python -m 两种方式）
_SOURCE_DIR = os.path.dirname(os.path.abspath(__file__))       # source/
_PROJECT_ROOT = os.path.dirname(_SOURCE_DIR)                   # source/ 的父目录
for _p in (_PROJECT_ROOT, _SOURCE_DIR):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from typing import Set

from source import route as source_route
from source.config import logger
from source.cookie_bootstrap import ensure_cookie_pool_seeded_from_file

# ── WebSocket 连接管理器（独立版）────────────────────────────────────
class ConnectionManager:
    def __init__(self):
        self.active_connections: Set[WebSocket] = set()

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.add(websocket)

    def disconnect(self, websocket: WebSocket):
        self.active_connections.discard(websocket)

    async def broadcast(self, message: dict):
        dead = []
        for conn in self.active_connections:
            try:
                await conn.send_json(message)
            except Exception:
                dead.append(conn)
        for conn in dead:
            self.active_connections.discard(conn)


manager = ConnectionManager()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info('货源管理模块独立服务启动')
    try:
        bootstrap_result = ensure_cookie_pool_seeded_from_file()
        logger.info('共享 Cookie 启动自检结果: %s', bootstrap_result)
    except Exception as error:
        logger.exception('共享 Cookie 启动自检失败: %s', error)
    yield
    logger.info('货源管理模块独立服务关闭')


app = FastAPI(
    title='货源管理模块',
    description='闲鱼货源采集任务管理 API（独立部署版）',
    version='1.0.0',
    lifespan=lifespan,
)

# ── CORS 配置 ────────────────────────────────────────────────────────
app.add_middleware(
    CORSMiddleware,
    allow_origins=['*'],
    allow_credentials=True,
    allow_methods=['*'],
    allow_headers=['*'],
)

# ── 注册路由 ─────────────────────────────────────────────────────────
app.include_router(source_route.router, prefix='/api', tags=['source'])


# ── WebSocket 端点 ───────────────────────────────────────────────────
@app.websocket('/ws/progress')
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            if data == 'ping':
                await websocket.send_json({'type': 'pong'})
    except WebSocketDisconnect:
        manager.disconnect(websocket)


app.state.manager = manager


@app.get('/api/health')
async def health_check():
    return {'status': 'ok', 'service': 'goofish-source'}


# ── 直接运行支持 ─────────────────────────────────────────────────────
if __name__ == '__main__':
    import uvicorn
    print('=' * 60)
    print('货源管理模块 - 独立启动')
    print('API 地址: http://localhost:8002')
    print('健康检查: http://localhost:8002/api/health')
    print('=' * 60)
    uvicorn.run(app, host='0.0.0.0', port=8002, log_level='info')

