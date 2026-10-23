import json
import logging
from datetime import datetime, timedelta
from typing import Optional

from source.config import SOURCE_MYSQL_CONFIG

logger = logging.getLogger('goofish.source.cookie_pool')

COOKIE_STATUS_ACTIVE = 0
COOKIE_STATUS_COOLING = 1
COOKIE_STATUS_EXPIRED = 2
COOKIE_STATUS_DISABLED = 3

COOKIE_RISK_COOLDOWN_MINUTES = 30


def _get_conn():
    try:
        import pymysql
    except ModuleNotFoundError as error:
        raise RuntimeError('当前 Python 运行环境缺少 pymysql，请在 Python 3.12 环境安装后重试') from error

    return pymysql.connect(**SOURCE_MYSQL_CONFIG, cursorclass=pymysql.cursors.DictCursor)


def _translate_db_error(error: Exception) -> RuntimeError:
    message = str(error)
    if 'app_source_cookie' in message and ('doesn\'t exist' in message or '不存在' in message):
        return RuntimeError('共享 Cookie 池表不存在，请先运行 script/sql/apply-source-cookie-pool.ps1')
    return RuntimeError(message)


def _dumps_cookie(cookies: dict) -> str:
    return json.dumps(cookies, ensure_ascii=False, sort_keys=True)


def normalize_cookie_record(cookies: dict) -> dict:
    if not isinstance(cookies, dict):
        raise ValueError('cookies 必须是 dict')

    unb = str(cookies.get('unb') or '').strip()
    if not unb:
        raise ValueError('Cookie 缺少 unb，无法写入共享池')

    return {
        'unb': unb,
        'cookie_json': _dumps_cookie(cookies),
        'status': COOKIE_STATUS_ACTIVE,
    }


def choose_available_cookie(
    records: list[dict],
    now: Optional[datetime] = None,
    excluded_ids: Optional[set[int]] = None,
) -> Optional[dict]:
    now = now or datetime.now()
    excluded_ids = excluded_ids or set()
    candidates = []
    for record in records:
        if record.get('status') != COOKIE_STATUS_ACTIVE:
            continue
        cooldown_until = record.get('cooldown_until')
        if cooldown_until and cooldown_until > now:
            continue
        if record.get('id') in excluded_ids:
            continue
        candidates.append(record)

    if not candidates:
        return None

    def _sort_key(record: dict):
        last_refresh_at = record.get('last_refresh_at') or datetime.min
        last_used_at = record.get('last_used_at') or datetime.min
        return (
            -last_refresh_at.toordinal(),
            -(last_refresh_at.hour * 3600 + last_refresh_at.minute * 60 + last_refresh_at.second),
            last_used_at.toordinal(),
            last_used_at.hour * 3600 + last_used_at.minute * 60 + last_used_at.second,
            record.get('id') or 0,
        )

    return sorted(candidates, key=_sort_key)[0]


def save_cookie_to_pool(cookies: dict) -> None:
    record = normalize_cookie_record(cookies)
    now = datetime.now()
    conn = _get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                '''
                INSERT INTO app_source_cookie (
                    unb, cookie_json, status,
                    last_refresh_at, cooldown_until,
                    creator, updater, tenant_id
                ) VALUES (%s, %s, %s, %s, NULL, 'python', 'python', 0)
                ON DUPLICATE KEY UPDATE
                    cookie_json = VALUES(cookie_json),
                    status = VALUES(status),
                    last_refresh_at = VALUES(last_refresh_at),
                    cooldown_until = NULL,
                    updater = 'python',
                    deleted = b'0'
                ''',
                (
                    record['unb'],
                    record['cookie_json'],
                    record['status'],
                    now,
                )
            )
        conn.commit()
        logger.info('共享 Cookie 池已写入/覆盖: unb=%s', record['unb'])
    except Exception as error:
        conn.rollback()
        raise _translate_db_error(error) from error
    finally:
        conn.close()



def has_available_cookie_in_pool() -> bool:
    now = datetime.now()
    conn = _get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                '''
                SELECT id, status, cooldown_until
                FROM app_source_cookie
                WHERE deleted = 0
                  AND status = %s
                  AND (cooldown_until IS NULL OR cooldown_until <= %s)
                ORDER BY id ASC
                ''',
                (COOKIE_STATUS_ACTIVE, now)
            )
            records = cur.fetchall()
        return choose_available_cookie(records, now=now) is not None
    except Exception as error:
        raise _translate_db_error(error) from error
    finally:
        conn.close()
def acquire_cookie_from_pool(excluded_cookie_ids: Optional[set[int]] = None) -> Optional[dict]:
    now = datetime.now()
    conn = _get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                '''
                SELECT id, unb, cookie_json, status,
                       last_used_at, last_refresh_at, cooldown_until
                FROM app_source_cookie
                WHERE deleted = 0
                  AND status = %s
                  AND (cooldown_until IS NULL OR cooldown_until <= %s)
                ORDER BY last_refresh_at DESC, last_used_at ASC, id ASC
                ''',
                (COOKIE_STATUS_ACTIVE, now)
            )
            records = cur.fetchall()
            record = choose_available_cookie(
                records, now=now, excluded_ids=excluded_cookie_ids
            )
            if not record:
                conn.rollback()
                return None

            cur.execute(
                '''
                UPDATE app_source_cookie
                SET last_used_at = %s,
                    updater = 'python'
                WHERE id = %s
                  AND deleted = 0
                ''',
                (now, record['id'])
            )
        conn.commit()

        cookies = json.loads(record['cookie_json'])
        record['cookies'] = cookies
        return record
    except Exception as error:
        conn.rollback()
        raise _translate_db_error(error) from error
    finally:
        conn.close()


def record_runtime_refresh(cookie_id: int, cookies: dict) -> None:
    now = datetime.now()
    conn = _get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                '''
                UPDATE app_source_cookie
                SET cookie_json = %s,
                    status = %s,
                    last_refresh_at = %s,
                    cooldown_until = NULL,
                    updater = 'python'
                WHERE id = %s
                  AND deleted = 0
                ''',
                (_dumps_cookie(cookies), COOKIE_STATUS_ACTIVE, now, cookie_id)
            )
        conn.commit()
        logger.info('运行中自动回写 Cookie 成功: id=%s', cookie_id)
    except Exception as error:
        conn.rollback()
        raise _translate_db_error(error) from error
    finally:
        conn.close()


def mark_cookie_failure(cookie_id: int, status: int, cooldown_until: Optional[datetime] = None) -> None:
    conn = _get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                '''
                UPDATE app_source_cookie
                SET status = %s,
                    cooldown_until = %s,
                    updater = 'python'
                WHERE id = %s
                  AND deleted = 0
                ''',
                (status, cooldown_until, cookie_id)
            )
        conn.commit()
    except Exception as error:
        conn.rollback()
        raise _translate_db_error(error) from error
    finally:
        conn.close()


def mark_cookie_user_validate(cookie_id: int) -> None:
    mark_cookie_failure(cookie_id, COOKIE_STATUS_EXPIRED)


def mark_cookie_risk_control(cookie_id: int) -> None:
    cooldown_until = datetime.now() + timedelta(minutes=COOKIE_RISK_COOLDOWN_MINUTES)
    mark_cookie_failure(cookie_id, COOKIE_STATUS_COOLING, cooldown_until)

