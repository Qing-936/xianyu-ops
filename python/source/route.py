"""
货源管理模块 - API 路由
对接 Java 端，提供任务执行、状态查询、数据查询接口

路由前缀: /api/source
"""

import logging
import asyncio
from typing import Optional
from concurrent.futures import ThreadPoolExecutor

from fastapi import APIRouter, Request
from pydantic import BaseModel

from source.service import (
    execute_source_task,
    execute_detail_only,
    get_task_status,
    get_executions,
    get_items,
    get_item_detail,
    add_to_inventory,
    manual_import_by_url,
)
from source.cookie_refresh import (
    get_cookie_refresh_status,
    start_cookie_refresh,
)


logger = logging.getLogger('goofish.api.source')
router = APIRouter()

# 线程池，用于执行阻塞式货源任务（同步 API 调用 + DB 操作）
_source_executor = ThreadPoolExecutor(max_workers=4)


# ── 请求模型 ──────────────────────────────────────────────────────────────────

class ExecuteRequest(BaseModel):
    task_id: int
    keyword: Optional[str] = None       # 可选，直接传入搜索关键词覆盖任务配置
    fetch_count: Optional[int] = None   # 可选，覆盖任务配置的获取条数
    switch_cookie: Optional[bool] = False


class ExecuteDetailRequest(BaseModel):
    task_id: int
    execution_no: int




class ManualImportRequest(BaseModel):
    detail_url: str
    inventory_user: Optional[str] = 'system'
    tenant_id: Optional[int] = 1


@router.post('/source/inventory/manual-import')
async def inventory_manual_import(req: ManualImportRequest, request: Request):
    """从闲鱼商品链接解析详情并直接写入货源库。"""
    trace_id = request.headers.get('X-Trace-Id', '')
    logger.info('人工入库请求: detail_url=%s, tenant_id=%s, trace_id=%s',
                req.detail_url, req.tenant_id, trace_id)
    try:
        inventory_id = await asyncio.get_running_loop().run_in_executor(
            _source_executor,
            lambda: manual_import_by_url(
                detail_url=req.detail_url,
                inventory_user=req.inventory_user,
                tenant_id=req.tenant_id,
            ),
        )
        return {
            'success': True,
            'message': '入库成功',
            'data': {'inventory_id': inventory_id},
        }
    except ValueError as error:
        logger.warning('人工入库业务异常: %s', error)
        return {'success': False, 'message': str(error)}
    except Exception as error:
        logger.error('人工入库系统异常: %s', error, exc_info=True)
        return {'success': False, 'message': f'系统错误: {error}'}

@router.post('/source/execute')
async def source_execute(
    req: ExecuteRequest,
    request: Request,
):
    """
    触发货源任务执行（后台线程池执行，立即返回）
    Java 端创建任务后调用此接口，传递 task_id
    """
    manager = request.app.state.manager
    loop = asyncio.get_running_loop()

    # 在线程池中执行阻塞任务，不阻塞 FastAPI 事件循环
    loop.run_in_executor(
        _source_executor,
        lambda: execute_source_task(
            req.task_id, manager, loop,
            keyword_override=req.keyword,
            switch_cookie=bool(req.switch_cookie),
            fetch_count_override=req.fetch_count,
        )
    )

    kw_info = f', keyword={req.keyword}' if req.keyword else ''
    logger.info(f'货源任务已提交: task_id={req.task_id}{kw_info}')
    return {'success': True, 'message': f'任务已提交执行, task_id={req.task_id}'}


@router.post('/source/cookie/refresh')
async def source_cookie_refresh(request: Request):
    trace_id = request.headers.get('X-Trace-Id', '')
    logger.info('生成共享 Cookie 刷新二维码: trace_id=%s', trace_id)
    try:
        session = await asyncio.get_running_loop().run_in_executor(
            _source_executor,
            start_cookie_refresh,
        )
        return {
            'success': True,
            'message': '二维码已生成',
            'data': session,
        }
    except Exception as error:
        logger.error('生成共享 Cookie 刷新二维码失败: %s', error, exc_info=True)
        return {
            'success': False,
            'message': f'生成二维码失败: {error}',
        }


@router.get('/source/cookie/refresh/{session_id}')
async def source_cookie_refresh_status(session_id: str):
    try:
        return {
            'success': True,
            'message': '查询成功',
            'data': get_cookie_refresh_status(session_id),
        }
    except KeyError:
        return {
            'success': False,
            'message': '扫码会话不存在或已失效',
        }


@router.post('/source/execute-detail')
async def source_execute_detail(req: ExecuteDetailRequest, request: Request):
    """
    单独触发详情采集（对某批次 is_valid=1 的商品）
    用于分步触发搜索和详情的场景
    """
    manager = request.app.state.manager
    loop = asyncio.get_running_loop()

    loop.run_in_executor(
        _source_executor,
        lambda: execute_detail_only(req.task_id, req.execution_no, manager, loop)
    )

    logger.info(f'详情采集已提交: task_id={req.task_id}, execution_no={req.execution_no}')
    return {
        'success': True,
        'message': f'详情采集已提交, task_id={req.task_id}, batch={req.execution_no}'
    }


# ── 状态查询 ──────────────────────────────────────────────────────────────────

@router.get('/source/task/{task_id}')
async def source_task_status(task_id: int):
    """查询任务信息 + 执行历史"""
    return get_task_status(task_id)


@router.get('/source/execution/{task_id}')
async def source_executions(task_id: int):
    """查询某任务的所有批次汇总"""
    return get_executions(task_id)


# ── 数据查询 ──────────────────────────────────────────────────────────────────

@router.get('/source/items')
async def source_items(
    task_id: int,
    execution_no: Optional[int] = None,
    is_valid: Optional[int] = None,
    limit: int = 200,
):
    """
    查询搜索结果
    - task_id: 任务ID（必填）
    - execution_no: 批次号（可选）
    - is_valid: 有效标记 0/1（可选）
    - limit: 返回条数上限
    """
    return get_items(task_id, execution_no, is_valid, limit)


@router.get('/source/item-detail')
async def source_item_detail(
    task_id: int,
    external_item_id: Optional[str] = None,
    item_row_id: Optional[int] = None,
    limit: int = 200,
):
    """
    查询商品详情
    - task_id: 任务ID（必填）
    - external_item_id: 外部商品ID（可选）
    - item_row_id: app_source_item.id（可选）
    - limit: 返回条数上限
    """
    return get_item_detail(task_id, external_item_id, item_row_id, limit)


# ── 货源入库 ──────────────────────────────────────────────────────────────────

class AddInventoryRequest(BaseModel):
    detail_id: int
    inventory_user: Optional[str] = 'system'
    tenant_id: Optional[int] = 1


@router.post('/source/inventory/add')
async def inventory_add(
    req: AddInventoryRequest,
    request: Request,
):
    """
    单个商品入库
    
    请求体:
    {
        "detail_id": 123,
        "inventory_user": "admin",
        "tenant_id": 1
    }
    
    响应:
    {
        "success": true,
        "message": "入库成功",
        "data": {
            "inventory_id": 456
        }
    }
    """
    try:
        trace_id = request.headers.get('X-Trace-Id', '')
        logger.info('入库请求: detail_id=%s, tenant_id=%s, trace_id=%s',
                    req.detail_id, req.tenant_id, trace_id)
        inventory_id = add_to_inventory(
            detail_id=req.detail_id,
            inventory_user=req.inventory_user,
            tenant_id=req.tenant_id,
        )
        
        return {
            'success': True,
            'message': '入库成功',
            'data': {'inventory_id': inventory_id}
        }
        
    except ValueError as e:
        logger.warning(f'入库业务异常: {e}')
        return {
            'success': False,
            'message': str(e)
        }
        
    except Exception as e:
        logger.error(f'入库系统异常: {e}', exc_info=True)
        return {
            'success': False,
            'message': f'系统错误: {str(e)}'
        }

