import threading
import uuid
from typing import Callable, Optional

from source.goofish_utils import (
    complete_qrcode_login_session,
    create_qrcode_login_session,
)


_REFRESH_LOCK = threading.Lock()
_REFRESH_SESSIONS: dict[str, dict] = {}


def _default_login_factory() -> dict:
    context = create_qrcode_login_session(show_qrcode=False)
    return {
        "context": context,
        "qr_url": context["qr_url"],
        "expire_seconds": 120,
    }


def _serialize(record: dict) -> dict:
    return {
        "session_id": record["session_id"],
        "status": record["status"],
        "qr_url": record["qr_url"],
        "expire_seconds": record["expire_seconds"],
        "message": record["message"],
    }


def clear_cookie_refresh_sessions() -> None:
    with _REFRESH_LOCK:
        _REFRESH_SESSIONS.clear()


def start_cookie_refresh(
    login_factory: Optional[Callable[[], dict]] = None,
    login_completer: Optional[Callable[..., dict]] = None,
    start_async: bool = True,
    session_id: Optional[str] = None,
) -> dict:
    login_factory = login_factory or _default_login_factory
    login_completer = login_completer or complete_qrcode_login_session

    login_session = login_factory()
    refresh_session_id = session_id or uuid.uuid4().hex
    record = {
        "session_id": refresh_session_id,
        "status": "pending",
        "qr_url": login_session["qr_url"],
        "expire_seconds": int(login_session.get("expire_seconds") or 120),
        "message": "请在闲鱼 APP 中扫码并确认登录",
        "context": login_session["context"],
        "login_completer": login_completer,
    }

    with _REFRESH_LOCK:
        _REFRESH_SESSIONS[refresh_session_id] = record

    if start_async:
        threading.Thread(
            target=run_cookie_refresh_session,
            args=(refresh_session_id,),
            daemon=True,
            name=f"cookie-refresh-{refresh_session_id[:8]}",
        ).start()

    return _serialize(record)


def run_cookie_refresh_session(
    session_id: str,
    login_completer: Optional[Callable[..., dict]] = None,
    poll_interval: float = 3.0,
    timeout: float = 120.0,
) -> dict:
    with _REFRESH_LOCK:
        record = _REFRESH_SESSIONS.get(session_id)
    if record is None:
        raise KeyError(f"cookie refresh session not found: {session_id}")

    completer = login_completer or record["login_completer"]
    try:
        try:
            result = completer(record["context"], poll_interval=poll_interval, timeout=timeout)
        except TypeError:
            result = completer(record["context"])
        unb = result.get("unb") if isinstance(result, dict) else None
        with _REFRESH_LOCK:
            record["status"] = "success"
            record["message"] = (
                f"扫码成功，Cookie 已回写共享池（unb={unb}）"
                if unb else "扫码成功，Cookie 已回写共享池"
            )
    except TimeoutError:
        with _REFRESH_LOCK:
            record["status"] = "expired"
            record["message"] = "二维码已过期，请重新生成"
    except Exception as error:
        with _REFRESH_LOCK:
            record["status"] = "failed"
            record["message"] = str(error) or "扫码登录失败"

    return get_cookie_refresh_status(session_id)


def get_cookie_refresh_status(session_id: str) -> dict:
    with _REFRESH_LOCK:
        record = _REFRESH_SESSIONS.get(session_id)
        if record is None:
            raise KeyError(f"cookie refresh session not found: {session_id}")
        return _serialize(record)
