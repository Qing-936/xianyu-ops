from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

from source.config import COOKIE_FILE, logger
from source.cookie_pool import has_available_cookie_in_pool, save_cookie_to_pool


def ensure_cookie_pool_seeded_from_file(cookie_file: Optional[Path] = None) -> dict:
    target = Path(cookie_file) if cookie_file else Path(COOKIE_FILE)

    if has_available_cookie_in_pool():
        logger.info('共享 Cookie 池已有可用记录，跳过本地 cookie.json 自动导入')
        return {'seeded': False, 'reason': 'pool_not_empty'}

    if not target.exists():
        logger.warning('共享 Cookie 池为空，且本地 cookie.json 不存在: %s', target)
        return {'seeded': False, 'reason': 'cookie_file_missing'}

    try:
        cookies = json.loads(target.read_text(encoding='utf-8'))
    except Exception as error:
        logger.error('共享 Cookie 池为空，但本地 cookie.json 读取失败: %s', error)
        return {'seeded': False, 'reason': 'cookie_file_invalid'}

    if not isinstance(cookies, dict) or not str(cookies.get('unb') or '').strip():
        logger.warning('共享 Cookie 池为空，但本地 cookie.json 缺少有效 unb，跳过导入')
        return {'seeded': False, 'reason': 'cookie_missing_unb'}

    save_cookie_to_pool(cookies)
    logger.info('共享 Cookie 池为空，已从本地 cookie.json 自动补入一条共享 Cookie: %s', target)
    return {'seeded': True, 'reason': 'seeded_from_file'}
