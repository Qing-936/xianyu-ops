"""Bounded collection of locally filtered Goofish search items."""

from dataclasses import dataclass
import logging
import math
import time
from typing import Any, Callable, Dict, List, Optional

from source.goofish_search_api import (
    RESPONSE_SUCCESS,
    build_price_filter,
    classify_response,
)


logger = logging.getLogger(__name__)


@dataclass
class SearchCollection:
    items: List[Dict[str, Any]]
    valid_items: List[Dict[str, Any]]
    scanned_count: int
    pages_scanned: int
    stop_reason: str


class GoofishSearchError(RuntimeError):
    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind


def _validate_positive_integer(value, name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f'{name} must be a positive integer')


def _response_message(response: Any) -> str:
    if not isinstance(response, dict):
        return str(response)
    ret = response.get('ret')
    if isinstance(ret, (list, tuple)):
        return ' '.join(str(value) for value in ret)
    return str(ret)


def _validate_optional_range(minimum, maximum, name: str) -> None:
    for value in (minimum, maximum):
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError(f'{name} bounds must be non-negative integers')
    if minimum is not None and maximum is not None and minimum > maximum:
        raise ValueError(f'{name} minimum must not exceed maximum')


def _matches_range(value, minimum, maximum, *, multiplier: int = 1) -> bool:
    if minimum is None and maximum is None:
        return True
    if isinstance(value, bool):
        return False
    try:
        normalized = round(value * multiplier)
    except (TypeError, ValueError, OverflowError):
        return False
    if minimum is not None and normalized < minimum:
        return False
    if maximum is not None and normalized > maximum:
        return False
    return True


def _is_locally_valid(
    item: Dict[str, Any],
    min_price_fen,
    max_price_fen,
    min_view_count,
    max_view_count,
    min_want_count,
    max_want_count,
) -> bool:
    return (
        _matches_range(item.get('price'), min_price_fen, max_price_fen, multiplier=100)
        and _matches_range(item.get('view_count'), min_view_count, max_view_count)
        and _matches_range(item.get('want_count'), min_want_count, max_want_count)
    )


def collect_search_items(
    api,
    *,
    keyword: str,
    target_valid_count: int,
    min_price_fen: Optional[int],
    max_price_fen: Optional[int],
    max_pages: int,
    rows_per_page: int,
    request_interval: float,
    min_view_count: Optional[int] = None,
    max_view_count: Optional[int] = None,
    min_want_count: Optional[int] = None,
    max_want_count: Optional[int] = None,
    resolve_item_metrics: Optional[Callable[[Dict[str, Any]], bool]] = None,
    on_progress: Optional[Callable[[Dict[str, Any]], None]] = None,
) -> SearchCollection:
    """Return unique parsed items until the valid-item target or a safe stop."""
    _validate_positive_integer(target_valid_count, 'target_valid_count')
    _validate_positive_integer(max_pages, 'max_pages')
    _validate_positive_integer(rows_per_page, 'rows_per_page')
    if (
        isinstance(request_interval, bool)
        or not isinstance(request_interval, (int, float))
        or not math.isfinite(request_interval)
        or request_interval < 0
    ):
        raise ValueError('request_interval must be non-negative')
    build_price_filter(min_price_fen, max_price_fen)
    _validate_optional_range(min_view_count, max_view_count, 'view_count')
    _validate_optional_range(min_want_count, max_want_count, 'want_count')

    items: List[Dict[str, Any]] = []
    valid_items: List[Dict[str, Any]] = []
    seen_item_ids = set()
    scanned_count = 0

    for page_number in range(1, max_pages + 1):
        response = api.search_items(
            keyword=keyword,
            page_number=page_number,
            rows_per_page=rows_per_page,
            min_price_fen=min_price_fen,
            max_price_fen=max_price_fen,
        )
        response_kind = classify_response(response)
        if response_kind != RESPONSE_SUCCESS:
            raise GoofishSearchError(response_kind, _response_message(response))

        data = response.get('data') if isinstance(response, dict) else None
        if not isinstance(data, dict) or not isinstance(data.get('resultList'), list):
            raise GoofishSearchError(
                'failure',
                f'第 {page_number} 页搜索响应结构无效：data.resultList 必须为列表',
            )
        result_list = data['resultList']

        stop_reason = None
        for wrapper in result_list:
            scanned_count += 1
            try:
                main = wrapper['data']['item']['main']
                parsed_item = api.parse_search_item(main)
                item_id = parsed_item.get('item_id')
            except (AttributeError, KeyError, TypeError, ValueError):
                logger.warning('Skipping malformed search result wrapper')
                continue

            if not isinstance(item_id, str) or not item_id or item_id in seen_item_ids:
                continue

            seen_item_ids.add(item_id)
            items.append(parsed_item)

            # 搜索列表不提供可靠的浏览量；存在计数筛选时，由调用方先用
            # 详情接口补全权威指标，再执行最终区间判断。
            if resolve_item_metrics is not None:
                if not _matches_range(
                    parsed_item.get('price'),
                    min_price_fen,
                    max_price_fen,
                    multiplier=100,
                ):
                    continue
                if not resolve_item_metrics(parsed_item):
                    continue

            if _is_locally_valid(
                parsed_item,
                min_price_fen,
                max_price_fen,
                min_view_count,
                max_view_count,
                min_want_count,
                max_want_count,
            ):
                valid_items.append(parsed_item)
                if len(valid_items) == target_valid_count:
                    stop_reason = 'target_reached'
                    break

        if on_progress is not None:
            on_progress({
                'page': page_number,
                'scanned': scanned_count,
                'unique': len(items),
                'valid': len(valid_items),
                'target': target_valid_count,
                'min_price_fen': min_price_fen,
                'max_price_fen': max_price_fen,
            })

        if stop_reason is not None:
            return SearchCollection(items, valid_items, scanned_count, page_number, stop_reason)
        if len(result_list) < rows_per_page:
            return SearchCollection(items, valid_items, scanned_count, page_number, 'result_exhausted')
        if page_number == max_pages:
            return SearchCollection(items, valid_items, scanned_count, page_number, 'max_pages_reached')
        if request_interval > 0:
            time.sleep(request_interval)

    raise RuntimeError('unreachable')
