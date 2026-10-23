"""
闲鱼搜索API封装模块（source 独立版）
基于 XianYuApis 项目 sign 签名算法，封装闲鱼搜索接口

核心功能：
- search_items: 搜索商品（支持翻页）
- get_item_detail: 获取商品详情
"""

import json
import logging
import time
from typing import Optional, List, Dict, Any

import requests

from source.config import (
    SEARCH_API_URL, SEARCH_API_NAME, ITEM_DETAIL_API_URL,
    ITEM_DETAIL_API_NAME, APP_KEY, ROWS_PER_PAGE,
    REQUEST_INTERVAL, REQUEST_TIMEOUT, MAX_PAGES, MTOP_HEADERS,
)
from source.goofish_utils import (
    generate_sign, trans_cookies, generate_device_id,
)

logger = logging.getLogger('goofish.source.search_api')

RESPONSE_SUCCESS = 'success'
RESPONSE_TOKEN_EXPIRED = 'token_expired'
RESPONSE_USER_VALIDATE = 'user_validate'
RESPONSE_RISK_CONTROL = 'risk_control'
RESPONSE_FAILURE = 'failure'


def classify_response(res_json):
    """将闲鱼响应分类为可供调用方处理的公开类别。"""
    if not isinstance(res_json, dict):
        return RESPONSE_FAILURE

    ret = res_json.get('ret')
    if isinstance(ret, (list, tuple)):
        ret_entries = [str(value) for value in ret]
    elif isinstance(ret, str):
        ret_entries = [ret]
    else:
        return RESPONSE_FAILURE

    ret_text = ' '.join(ret_entries)
    if not ret_text:
        return RESPONSE_FAILURE
    if 'RGV587' in ret_text:
        return RESPONSE_RISK_CONTROL
    if 'FAIL_SYS_TOKEN_EXOIRED' in ret_text or '令牌过期' in ret_text:
        return RESPONSE_TOKEN_EXPIRED
    if 'USER_VALIDATE' in ret_text:
        return RESPONSE_USER_VALIDATE
    for entry in ret_entries:
        if entry.split('::', 1)[0] == 'SUCCESS':
            return RESPONSE_SUCCESS
    return RESPONSE_FAILURE


def format_price_yuan(price_fen: int) -> str:
    """将分转换为签名请求所需的元文本，不使用浮点数。"""
    if isinstance(price_fen, bool) or not isinstance(price_fen, int) or price_fen < 0:
        raise ValueError('price_fen must be a non-negative integer')
    yuan, fen = divmod(price_fen, 100)
    if fen == 0:
        return str(yuan)
    return f'{yuan}.{fen:02d}'.rstrip('0')


def build_price_filter(min_price_fen, max_price_fen):
    """构建闲鱼价格筛选字段，内部价格单位始终为分。"""
    if min_price_fen is None and max_price_fen is None:
        return False, {}

    for price_fen in (min_price_fen, max_price_fen):
        if price_fen is None:
            continue
        if isinstance(price_fen, bool) or not isinstance(price_fen, int) or price_fen < 0:
            raise ValueError('prices must be non-negative integers')

    if min_price_fen is not None and max_price_fen is not None and min_price_fen > max_price_fen:
        raise ValueError('minimum price must not exceed maximum price')

    # 闲鱼搜索参数只在闭区间时下推；单边条件由本地筛选，避免构造未经验证的开放区间。
    if min_price_fen is None or max_price_fen is None:
        return False, {}

    return True, {
        'searchFilter': (
            f'priceRange:{format_price_yuan(min_price_fen)},'
            f'{format_price_yuan(max_price_fen)};'
        ),
    }


class GoofishSearchAPI:
    """闲鱼搜索API客户端"""

    def __init__(self, cookies: dict, on_cookie_refresh=None):
        self.session = requests.Session()
        self.session.cookies.update(cookies)
        self.cookies = cookies
        self.on_cookie_refresh = on_cookie_refresh
        unb = cookies.get('unb', '')
        self.device_id = generate_device_id(unb) if unb else ''
        logger.info(f'搜索API客户端初始化完成 (unb={unb}, device_id={self.device_id[:16]}...)')

    def _get_token(self) -> str:
        """从Cookie中提取 _m_h5_tk 的前半部分作为签名token"""
        tk = self.cookies.get('_m_h5_tk', '')
        if not tk:
            try:
                tk = self.session.cookies.get('_m_h5_tk', '')
            except Exception:
                pass
        return tk.split('_')[0] if tk else ''

    def _build_common_params(self, api_name: str, t: str, sign: str, spm_cnt: str = 'a21ybx.search.0.0') -> dict:
        """构建通用请求参数"""
        return {
            'jsv': '2.7.2',
            'appKey': APP_KEY,
            't': t,
            'sign': sign,
            'v': '1.0',
            'type': 'originaljson',
            'accountSite': 'xianyu',
            'dataType': 'json',
            'timeout': str(REQUEST_TIMEOUT * 1000),
            'api': api_name,
            'sessionOption': 'AutoLoginOnly',
            'spm_cnt': spm_cnt,
        }

    def _refresh_token_from_response(self, response):
        """从响应中刷新 _m_h5_tk token"""
        changed = False
        new_tk = response.cookies.get('_m_h5_tk')
        if new_tk:
            self.cookies['_m_h5_tk'] = new_tk
            self.session.cookies.set('_m_h5_tk', new_tk)
            logger.info(f'已刷新 _m_h5_tk token')
            changed = True
        new_tk_enc = response.cookies.get('_m_h5_tk_enc')
        if new_tk_enc:
            self.cookies['_m_h5_tk_enc'] = new_tk_enc
            self.session.cookies.set('_m_h5_tk_enc', new_tk_enc)
            changed = True
        if changed and self.on_cookie_refresh:
            self.on_cookie_refresh(self.cookies)

    def _is_token_expired(self, res_json: dict) -> bool:
        """兼容旧调用方的 token 过期检测。"""
        return classify_response(res_json) == RESPONSE_TOKEN_EXPIRED

    def _request_json(self, url, api_name, data_str, data, spm_cnt, request_name):
        """发送已签名请求，按响应类别和网络异常执行有限重试。"""
        token_refreshed = False
        network_failures = 0
        last_error = None

        for post_attempt in range(3):
            t = str(int(time.time() * 1000))
            token = self._get_token()
            sign = generate_sign(t, token, data_str)
            params = self._build_common_params(api_name, t, sign, spm_cnt=spm_cnt)

            try:
                response = self.session.post(
                    url,
                    params=params,
                    headers=MTOP_HEADERS,
                    data=data,
                    timeout=REQUEST_TIMEOUT,
                )
            except requests.exceptions.RequestException as error:
                last_error = error
                network_failures += 1
                if post_attempt == 2:
                    break
                time.sleep(REQUEST_INTERVAL * network_failures)
                continue

            try:
                res_json = response.json()
            except ValueError:
                logger.error(f'{request_name}响应不是有效 JSON')
                return {'ret': ['FAIL_INVALID_RESPONSE'], 'msg': '响应不是有效 JSON'}

            if not isinstance(res_json, dict):
                logger.error(f'{request_name}响应不是有效 JSON 对象')
                return {'ret': ['FAIL'], 'msg': '响应不是有效 JSON 对象'}

            if classify_response(res_json) == RESPONSE_TOKEN_EXPIRED and not token_refreshed:
                logger.warning(f'{request_name} Token过期，正在自动刷新...')
                self._refresh_token_from_response(response)
                token_refreshed = True
                continue

            return res_json

        logger.error(f'{request_name}请求异常: {last_error}')
        return {'ret': ['FAIL'], 'msg': str(last_error)}

    def search_items(
        self,
        keyword: str,
        page_number: int = 1,
        rows_per_page: int = ROWS_PER_PAGE,
        sort_field: str = '',
        sort_value: str = '',
        min_price_fen=None,
        max_price_fen=None,
    ) -> Dict[str, Any]:
        """搜索闲鱼商品"""
        logger.info(f'搜索商品: keyword="{keyword}", page={page_number}')
        from_filter, prop_value_str = build_price_filter(min_price_fen, max_price_fen)
        data_payload = {
            "pageNumber": page_number,
            "keyword": keyword,
            "fromFilter": from_filter,
            "rowsPerPage": rows_per_page,
            "sortValue": sort_value,
            "sortField": sort_field,
            "customDistance": "",
            "gps": "",
            "propValueStr": prop_value_str,
            "customGps": "",
            "searchReqFromPage": "pcSearch",
            "extraFilterValue": "{}",
            "userPositionJson": "{}",
        }

        data_str = json.dumps(data_payload, separators=(',', ':'), ensure_ascii=False)

        data = {'data': data_str}
        res_json = self._request_json(
            SEARCH_API_URL,
            SEARCH_API_NAME,
            data_str,
            data,
            'a21ybx.search.0.0',
            '搜索API',
        )
        ret_list = res_json.get('ret', [])
        logger.info(f'搜索API响应: ret={ret_list}')
        return res_json

    def search_multi_pages(
        self,
        keyword: str,
        max_pages: int = MAX_PAGES,
        rows_per_page: int = ROWS_PER_PAGE,
    ) -> List[Dict[str, Any]]:
        """多页搜索闲鱼商品"""
        logger.info(f'开始多页搜索: keyword="{keyword}", max_pages={max_pages}')
        all_items = []

        for page in range(1, max_pages + 1):
            print(f'[搜索] 第 {page}/{max_pages} 页, 关键词: "{keyword}"')

            result = self.search_items(
                keyword=keyword,
                page_number=page,
                rows_per_page=rows_per_page,
            )

            ret_list = result.get('ret', [])
            if ret_list and 'SUCCESS' not in ret_list[0]:
                logger.warning(f'第{page}页请求失败: {ret_list}')
                print(f'[搜索] 第{page}页请求失败: {ret_list}')
                break

            data = result.get('data', {})
            if not data:
                logger.warning(f'第{page}页无数据返回')
                print(f'[搜索] 第{page}页无数据返回')
                break

            result_list = data.get('resultList', [])
            if not result_list:
                logger.info(f'第{page}页无更多结果')
                print(f'[搜索] 第{page}页无更多结果')
                break

            page_items = []
            for item_wrapper in result_list:
                try:
                    main = item_wrapper['data']['item']['main']
                    page_items.append(main)
                except (KeyError, TypeError):
                    continue

            all_items.extend(page_items)
            logger.info(f'第{page}页获取 {len(page_items)} 条结果')
            print(f'[搜索] 第{page}页获取 {len(page_items)} 条结果')

            if len(page_items) < rows_per_page:
                logger.info(f'本页结果不足 {rows_per_page} 条，已是最后一页')
                print(f'[搜索] 本页结果不足 {rows_per_page} 条，已是最后一页')
                break

            total_results = data.get('totalResults', 0)
            if total_results > 0 and page * rows_per_page >= total_results:
                logger.info(f'已获取全部结果 (共{total_results}条)')
                print(f'[搜索] 已获取全部结果 (共{total_results}条)')
                break

            if page < max_pages:
                time.sleep(REQUEST_INTERVAL)

        logger.info(f'多页搜索完成，共获取 {len(all_items)} 条商品信息')
        print(f'[搜索] 共获取 {len(all_items)} 条商品信息')
        return all_items

    def get_item_detail(self, item_id: str) -> Dict[str, Any]:
        """获取商品详情"""
        logger.info(f'获取商品详情: item_id={item_id}')
        data_str = json.dumps({"itemId": item_id}, separators=(',', ':'))

        data = {'data': data_str}
        res_json = self._request_json(
            ITEM_DETAIL_API_URL,
            ITEM_DETAIL_API_NAME,
            data_str,
            data,
            'a21ybx.item.0.0',
            '详情API',
        )
        ret = res_json.get('ret', [])
        logger.info(f'详情API响应: item_id={item_id}, ret={ret}')
        return res_json

    @staticmethod
    def parse_search_item(raw_item: dict) -> dict:
        """解析搜索结果中的单条商品数据"""
        exc = raw_item.get('exContent', {}) or {}
        dp = exc.get('detailParams', {}) or {}
        click_args = raw_item.get('clickParam', {}).get('args', {}) or {}

        pic_url = exc.get('picUrl', '')
        if pic_url and pic_url.startswith('//'):
            pic_url = 'https:' + pic_url
        elif pic_url and pic_url.startswith('http://'):
            pic_url = pic_url.replace('http://', 'https://', 1)

        price_str = dp.get('soldPrice', '')
        if not price_str and isinstance(exc.get('price', ''), list):
            for p_item in exc['price']:
                if isinstance(p_item, dict) and p_item.get('type') == 'integer':
                    price_str = p_item.get('text', '')
                    break
        try:
            price = float(price_str) if price_str else 0.0
        except (ValueError, TypeError):
            price = 0.0

        want = exc.get('want', {})
        want_count = 0
        if isinstance(want, dict):
            want_count = int(want.get('count', 0) or 0)
        elif isinstance(want, (int, str)):
            try:
                want_count = int(want)
            except (ValueError, TypeError):
                pass

        # 浏览量：尝试从多个位置提取
        view_count = 0
        for _vc_key in ('browseCnt', 'viewCount', 'viewCnt', 'readCount'):
            _vc_val = exc.get(_vc_key) or dp.get(_vc_key) or click_args.get(_vc_key)
            if _vc_val:
                try:
                    view_count = int(_vc_val)
                    if view_count > 0:
                        break
                except (ValueError, TypeError):
                    pass

        publish_time = click_args.get('publishTime', '')
        if publish_time:
            try:
                ts = int(publish_time) / 1000
                from datetime import datetime
                publish_time = datetime.fromtimestamp(ts).strftime('%Y-%m-%d %H:%M:%S')
            except (ValueError, TypeError, OSError):
                pass

        want_from_tags = 0
        fish_tags = exc.get('fishTags', {}) or {}
        if isinstance(fish_tags, dict):
            for _rk, _rv in fish_tags.items():
                if isinstance(_rv, dict) and 'tagList' in _rv:
                    for _t in _rv['tagList']:
                        if isinstance(_t, dict) and 'data' in _t:
                            _c = _t['data'].get('content', '')
                            if '想要' in _c and '人' in _c:
                                import re
                                _m = re.search(r'(\d+)人想要', _c)
                                if _m:
                                    want_from_tags = int(_m.group(1))
        if want_from_tags > 0:
            want_count = want_from_tags

        # 从 fishTags 中提取浏览量文本（如 "1234人浏览"）
        if view_count == 0 and isinstance(fish_tags, dict):
            for _rk, _rv in fish_tags.items():
                if isinstance(_rv, dict) and 'tagList' in _rv:
                    for _t in _rv['tagList']:
                        if isinstance(_t, dict) and 'data' in _t:
                            _c = _t['data'].get('content', '')
                            if '浏览' in _c:
                                import re as _re2
                                _m = _re2.search(r'(\d+)', _c)
                                if _m:
                                    try:
                                        view_count = int(_m.group(1))
                                    except (ValueError, TypeError):
                                        pass

        tagname_str = click_args.get('tagname', '')
        tag_str = click_args.get('tag', '')
        is_free_shipping = '包邮' in tagname_str or 'freeship' in tag_str
        if not is_free_shipping and isinstance(fish_tags, dict):
            for _rk, _rv in fish_tags.items():
                if isinstance(_rv, dict) and 'tagList' in _rv:
                    for _t in _rv['tagList']:
                        if isinstance(_t, dict) and 'data' in _t:
                            if '包邮' in str(_t['data'].get('content', '')):
                                is_free_shipping = True

        is_yhb = False
        if isinstance(fish_tags, dict):
            for _rk, _rv in fish_tags.items():
                if isinstance(_rv, dict) and 'tagList' in _rv:
                    for _t in _rv['tagList']:
                        if isinstance(_t, dict) and 'data' in _t:
                            if '验货' in str(_t['data'].get('content', '')):
                                is_yhb = True

        seller_credit_tag = ''
        if tagname_str:
            parts = [p.strip() for p in tagname_str.split('/')]
            credit_parts = [p for p in parts if p not in ('包邮',)]
            seller_credit_tag = '/'.join(credit_parts) if credit_parts else ''

        import re as _re
        ori_price_str = exc.get('oriPrice', '')
        original_price = 0.0
        if ori_price_str:
            _m = _re.search(r'[\d.]+', str(ori_price_str))
            if _m:
                try:
                    original_price = float(_m.group())
                except (ValueError, TypeError):
                    pass

        price_drop_tag = ''
        ship_speed = ''
        if isinstance(fish_tags, dict):
            for _rk, _rv in fish_tags.items():
                if isinstance(_rv, dict) and 'tagList' in _rv:
                    for _t in _rv['tagList']:
                        if isinstance(_t, dict) and 'data' in _t:
                            _td = _t['data']
                            _lid = _td.get('labelId', '')
                            _content = _td.get('content', '')
                            if _lid == '749':
                                price_drop_tag = _content
                            elif _lid == '756':
                                price_drop_tag = _content
                            elif _lid == '751':
                                ship_speed = _content

        review_count = ''
        good_rate = ''
        seller_level = ''
        shop_label = exc.get('userFishShopLabel', {}) or {}
        for _sl in shop_label.get('tagList', []):
            _sc = _sl.get('data', {}).get('content', '')
            if '评价' in _sc:
                review_count = _sc
            elif '好评' in _sc:
                good_rate = _sc
            elif '优质' in _sc or '个人' in _sc or '卖家' in _sc:
                seller_level = _sc

        # 尝试从 fishTags 中补充卖家级别
        if not seller_level and isinstance(fish_tags, dict):
            for _rk, _rv in fish_tags.items():
                if isinstance(_rv, dict) and 'tagList' in _rv:
                    for _t in _rv['tagList']:
                        if isinstance(_t, dict) and 'data' in _t:
                            _sc = _t['data'].get('content', '')
                            if '优质卖家' in _sc or '个人卖家' in _sc:
                                seller_level = _sc
                                break

        return {
            'item_id': exc.get('itemId', '') or dp.get('itemId', ''),
            'title': exc.get('title', '') or dp.get('title', ''),
            'price': price,
            'original_price': original_price,
            'pic_url': pic_url,
            'description': dp.get('title', ''),
            'location': exc.get('area', ''),
            'seller_id': click_args.get('seller_id', ''),
            'seller_name': exc.get('userNickName', '') or dp.get('userNick', ''),
            'seller_avatar': exc.get('userAvatarUrl', ''),
            'view_count': view_count,
            'want_count': want_count,
            'item_status': '',
            'publish_time': str(publish_time),
            'category_name': '',
            'tag_list': json.dumps(fish_tags, ensure_ascii=False) if fish_tags else '',
            'sold_count': 0,
            'collect_count': 0,
            'quantity': 0,
            'transport_fee': '',
            'cpv_labels': '',
            'item_type': dp.get('itemType', ''),
            'is_free_shipping': is_free_shipping,
            'seller_city': '',
            'seller_signature': '',
            'seller_sold_count': 0,
            'seller_reg_days': 0,
            'seller_zhima_auth': False,
            'seller_credit_tag': seller_credit_tag,
            'seller_level': seller_level,
            'seller_reply_ratio': '',
            'seller_good_rate': good_rate,
            'seller_remark_good': 0,
            'seller_remark_bad': 0,
            'is_yhb': is_yhb,
            'yhb_desc': '',
            'seller_review_count': review_count,
            'price_drop_tag': price_drop_tag,
            'ship_speed': ship_speed,
            'has_video': exc.get('showVideoIcon', False),
        }

    @staticmethod
    def parse_detail_item(detail_data: dict) -> dict:
        """解析详情API返回的商品数据"""
        item_do = detail_data.get('itemDO', {}) or {}
        seller_do = detail_data.get('sellerDO', {}) or {}

        sold_price = item_do.get('soldPrice', '')
        try:
            price = float(sold_price) if sold_price else 0.0
        except (ValueError, TypeError):
            price = 0.0

        original_price_str = item_do.get('originalPrice', '')
        try:
            original_price = float(original_price_str) if original_price_str else 0.0
        except (ValueError, TypeError):
            original_price = 0.0

        pic_url = ''
        image_infos = item_do.get('imageInfos', [])
        if isinstance(image_infos, list) and image_infos:
            for img in image_infos:
                if isinstance(img, dict) and img.get('major'):
                    pic_url = img.get('url', '')
                    break
            if not pic_url:
                pic_url = image_infos[0].get('url', '') if isinstance(image_infos[0], dict) else ''
        if pic_url and pic_url.startswith('http://'):
            pic_url = pic_url.replace('http://', 'https://', 1)

        gmt_create = item_do.get('gmtCreate', 0)
        publish_time = ''
        if gmt_create:
            try:
                from datetime import datetime
                publish_time = datetime.fromtimestamp(int(gmt_create) / 1000).strftime('%Y-%m-%d %H:%M:%S')
            except (ValueError, TypeError, OSError):
                pass

        cpv_labels_list = []
        item_condition = ''
        function_status = ''
        brand = ''
        for cpv in item_do.get('cpvLabels', []):
            if isinstance(cpv, dict):
                cpv_labels_list.append({
                    'property': cpv.get('propertyName', ''),
                    'value': cpv.get('valueName', ''),
                })
                prop = cpv.get('propertyName', '')
                value = cpv.get('valueName', '')
                if prop == '成色':
                    item_condition = value
                elif prop == '功能状态':
                    function_status = value
                elif prop == '品牌':
                    brand = value

        is_free_shipping = False
        for pt in item_do.get('priceRelativeTags', []):
            if isinstance(pt, dict) and '包邮' in pt.get('text', ''):
                is_free_shipping = True
                break
        for ct in item_do.get('commonTags', []):
            if isinstance(ct, dict) and '包邮' in ct.get('text', ''):
                is_free_shipping = True
                break

        is_yhb = False
        yhb_desc = ''
        appraise = item_do.get('appraiseInfo', {})
        if isinstance(appraise, dict) and appraise.get('serviceDetailTitle'):
            is_yhb = True
            yhb_desc = appraise.get('serviceDescription', '')

        seller_avatar = seller_do.get('portraitUrl', '')
        if seller_avatar and seller_avatar.startswith('http://'):
            seller_avatar = seller_avatar.replace('http://', 'https://', 1)

        seller_credit_tag = ''
        seller_zhima_level = ''
        zhima_info = seller_do.get('zhimaLevelInfo', {}) or {}
        if zhima_info.get('levelName'):
            seller_credit_tag = zhima_info.get('levelName', '')
        else:
            credit_tag = seller_do.get('idleFishCreditTag', {}) or {}
            if isinstance(credit_tag, dict):
                seller_credit_tag = credit_tag.get('trackParams', {}).get('sellerLevel', '')
        seller_zhima_level = zhima_info.get('levelCode', '')

        remark_do = seller_do.get('remarkDO', {}) or {}
        seller_remark_good = remark_do.get('sellerGoodRemarkCnt', 0) or 0
        seller_remark_bad = remark_do.get('sellerBadRemarkCnt', 0) or 0

        cat_dto = item_do.get('itemCatDTO', {}) or {}
        category_name = ''
        for cpv in item_do.get('cpvLabels', []):
            if isinstance(cpv, dict) and cpv.get('propertyName') == '分类':
                category_name = cpv.get('valueName', '')
                break

        promo_type = None
        promo_price = None
        promo_discount = None
        promo_do = item_do.get('promotionPriceDO', {}) or {}
        if promo_do.get('promotionName'):
            promo_type = promo_do.get('promotionName', '')
            try:
                promo_price = float(promo_do.get('promotionPriceMin', 0)) if promo_do.get('promotionPriceMin') else None
            except (ValueError, TypeError):
                pass
            if promo_price and price and promo_price < price:
                promo_discount = round(price - promo_price, 2)

        recycle_do = detail_data.get('recycleDO', {}) or {}
        trade_in_show = 1 if recycle_do.get('isShowRecycleHjbt') else 0
        hjbt_do = recycle_do.get('recycleHjbtDO', {}) or {}
        trade_in_desc = hjbt_do.get('desc', '') or None
        trade_in_url = hjbt_do.get('jumpUrl', '') or None

        seller_last_visit = seller_do.get('lastVisitTime', '')
        seller_reply_interval = seller_do.get('replyInterval', '') or None

        return {
            'item_id': str(item_do.get('itemId', '')),
            'title': item_do.get('title', ''),
            'price': price,
            'original_price': original_price,
            'pic_url': pic_url,
            'description': item_do.get('desc', ''),
            'location': seller_do.get('city', ''),
            'seller_id': str(seller_do.get('sellerId', '')),
            'seller_name': seller_do.get('nick', '') or seller_do.get('uniqueName', ''),
            'seller_avatar': seller_avatar,
            'view_count': int(item_do.get('browseCnt', 0) or 0),
            'want_count': int(item_do.get('wantCnt', 0) or 0),
            'item_status': item_do.get('itemStatusStr', ''),
            'publish_time': publish_time,
            'category_name': category_name,
            'tag_list': json.dumps(item_do.get('commonTags', []), ensure_ascii=False),
            'sold_count': int(item_do.get('soldCnt', 0) or 0),
            'collect_count': int(item_do.get('collectCnt', 0) or 0),
            'quantity': int(item_do.get('quantity', 0) or 0),
            'transport_fee': item_do.get('transportFee', ''),
            'cpv_labels': json.dumps(cpv_labels_list, ensure_ascii=False),
            'item_condition': item_condition,
            'function_status': function_status,
            'brand': brand,
            'item_type': item_do.get('itemType', ''),
            'is_free_shipping': is_free_shipping,
            'promo_type': promo_type,
            'promo_price': promo_price,
            'promo_discount': promo_discount,
            'trade_in_show': trade_in_show,
            'trade_in_desc': trade_in_desc,
            'trade_in_url': trade_in_url,
            'seller_city': seller_do.get('city', ''),
            'seller_signature': seller_do.get('signature', ''),
            'seller_sold_count': int(seller_do.get('hasSoldNumInteger', 0) or 0),
            'seller_reg_days': int(seller_do.get('userRegDay', 0) or 0),
            'seller_zhima_auth': bool(seller_do.get('zhimaAuth', False)),
            'seller_zhima_level': seller_zhima_level,
            'seller_credit_tag': seller_credit_tag,
            'seller_reply_ratio': seller_do.get('replyRatio24h', ''),
            'seller_reply_interval': seller_reply_interval,
            'seller_last_visit': seller_last_visit,
            'seller_good_rate': seller_do.get('newGoodRatioRate', ''),
            'seller_remark_good': seller_remark_good,
            'seller_remark_bad': seller_remark_bad,
            'is_yhb': is_yhb,
            'yhb_desc': yhb_desc,
        }
