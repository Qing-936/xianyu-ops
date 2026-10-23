"""
闲鱼工具函数模块（source 独立版）
基于 XianYuApis 项目 utils/goofish_utils.py 适配改造

核心功能：
- generate_sign: 生成闲鱼API签名 (MD5)
- generate_device_id: 生成设备ID
- trans_cookies: Cookie字符串转字典
- build_initial_cookies: 构建初始Cookie（无需登录）
- qrcode_login: 扫码登录获取登录态Cookie
"""

import json
import os
import subprocess
import time
import logging
from functools import partial
from pathlib import Path
from urllib.parse import quote

import requests
from source.cookie_pool import save_cookie_to_pool

logger = logging.getLogger('goofish.source.utils')

# 保存原始 Popen（execjs 在某些平台上需要 encoding 参数）
_OriginalPopen = subprocess.Popen
_PatchedPopen = partial(subprocess.Popen, encoding="utf-8")


def _patch_subprocess_popen():
    """临时应用 Popen 补丁（仅在调用 execjs 前使用）"""
    subprocess.Popen = _PatchedPopen


def _restore_subprocess_popen():
    """恢复原始 Popen"""
    subprocess.Popen = _OriginalPopen


# 初始化时应用补丁以编译 JS
_patch_subprocess_popen()
import execjs
_restore_subprocess_popen()

# ============================================================
# 加载闲鱼签名 JS（在 source/static/ 下查找）
# ============================================================
_HERE = Path(__file__).resolve().parent  # source/

_JS_PATHS = [
    _HERE / 'static' / 'goofish_js_version_2.js',
    _HERE.parent / 'static' / 'goofish_js_version_2.js',  # 回退到项目根目录
]

xianyu_js = None
for _p in _JS_PATHS:
    if _p.exists():
        with open(str(_p), 'r', encoding='utf-8') as f:
            xianyu_js = execjs.compile(f.read())
        logger.info(f'已加载签名JS: {_p}')
        break

if xianyu_js is None:
    logger.error(f'找不到闲鱼签名JS文件，已搜索路径: {[str(p) for p in _JS_PATHS]}')
    raise FileNotFoundError(
        f"找不到闲鱼签名JS文件，请确保 source/static/goofish_js_version_2.js 存在\n"
        f"已搜索路径: {[str(p) for p in _JS_PATHS]}"
    )


def trans_cookies(cookies_str: str) -> dict:
    """将Cookie字符串转为字典"""
    cookies = {}
    for item in cookies_str.split("; "):
        try:
            key, value = item.split('=', 1)
            cookies[key] = value
        except ValueError:
            continue
    logger.debug(f'Cookie字符串解析完成，共 {len(cookies)} 个字段')
    return cookies


def trans_cookies_str(cookies_dict: dict) -> str:
    """将Cookie字典转为字符串"""
    return "; ".join(f"{k}={v}" for k, v in cookies_dict.items())


def generate_device_id(user_id: str) -> str:
    """生成设备ID"""
    device_id = xianyu_js.call('generate_device_id', user_id)
    logger.debug(f'已生成设备ID (user_id={user_id})')
    return device_id


def generate_sign(t: str, token: str, data: str) -> str:
    """
    生成闲鱼API签名

    算法: MD5(token + "&" + timestamp + "&" + appKey + "&" + data)
    - t: 时间戳(毫秒)
    - token: Cookie中 _m_h5_tk 的前半部分
    - data: 请求参数JSON字符串
    """
    sign = xianyu_js.call('generate_sign', t, token, data)
    logger.debug(f'已生成API签名 (t={t})')
    return sign


def _gen_tfstk(timeout: int = 15) -> str:
    """使用Node.js生成tfstk"""
    script = _HERE / 'gen_tfstk.js'
    if not script.exists():
        logger.warning(f'gen_tfstk.js 不存在: {script}')
        return ''
    try:
        logger.info('正在通过Node.js生成tfstk...')
        out = subprocess.check_output(
            ['node', str(script)], timeout=timeout, stderr=subprocess.PIPE
        )
        tfstk = out.decode().strip()
        logger.info(f'tfstk生成成功 (长度={len(tfstk)})')
        return tfstk
    except Exception as e:
        logger.warning(f'tfstk生成失败: {e}')
        return ''


def build_initial_cookies() -> requests.Session:
    """
    纯HTTP获取闲鱼初始Cookie（不含登录态）
    返回已获取基础Cookie的Session
    """
    from source.config import MTOP_HEADERS, UA

    logger.info('正在获取闲鱼初始Cookie...')
    s = requests.Session()
    s.headers.update({'User-Agent': UA})

    # 获取cna
    s.get('https://log.mmstat.com/eg.js', timeout=10)
    cna = s.cookies.get('cna', domain='.mmstat.com')
    if cna:
        s.cookies.set('cna', cna, domain='.goofish.com', path='/')
        logger.info(f'已获取cna: {cna[:20]}...')

    # 调两次mtop获取 _m_h5_tk 和 cookie2
    for api in ['mtop.taobao.idlehome.home.webpc.feed',
                'mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get']:
        s.post(
            f'https://h5api.m.goofish.com/h5/{api}/1.0/',
            params={
                'jsv': '2.7.2', 'appKey': '34839810',
                't': str(int(time.time() * 1000)), 'sign': '', 'v': '1.0',
                'type': 'originaljson', 'dataType': 'json', 'timeout': '20000',
                'api': api, 'sessionOption': 'AutoLoginOnly',
                'spm_cnt': 'a21ybx.home.0.0',
            },
            data='data=%7B%7D', headers=MTOP_HEADERS, timeout=10,
        )
    logger.info('已完成mtop初始请求，获取基础Cookie')

    # tfstk
    tfstk = _gen_tfstk()
    if tfstk:
        s.cookies.set('tfstk', tfstk, domain='.goofish.com', path='/')

    return s


def _print_terminal_qrcode(qr_url: str) -> None:
    try:
        import qrcode as qr_lib
        import sys
        qr = qr_lib.QRCode(border=1, box_size=1)
        qr.add_data(qr_url)
        qr.make()
        matrix = qr.get_matrix()
        rows = len(matrix)
        lines = []
        for r in range(0, rows, 2):
            line = ''
            for c in range(len(matrix[r])):
                top = matrix[r][c]
                bot = matrix[r + 1][c] if r + 1 < rows else False
                if top and bot:
                    line += '█'
                elif top and not bot:
                    line += '▀'
                elif not top and bot:
                    line += '▄'
                else:
                    line += ' '
            lines.append(line)
        qr_str = '\n'.join(lines) + '\n'
        sys.stdout.buffer.write(qr_str.encode('utf-8', errors='replace'))
        sys.stdout.buffer.flush()
    except ImportError:
        logger.warning('qrcode库未安装，无法在终端显示二维码')
        print('[扫码登录] pip install qrcode 可在终端显示二维码')


def _persist_login_cookies(session: requests.Session, cookie_file: str) -> dict:
    cookies_dict = {}
    for cookie in session.cookies:
        if cookie.domain and ('.goofish.com' in cookie.domain or '.mmstat.com' in cookie.domain):
            cookies_dict[cookie.name] = cookie.value

    with open(cookie_file, 'w', encoding='utf-8') as file:
        json.dump(cookies_dict, file, ensure_ascii=False, indent=2)
    logger.info(f'Cookie已保存到: {cookie_file} (共 {len(cookies_dict)} 个字段)')
    print(f'[扫码登录] Cookie已保存到: {cookie_file}')

    try:
        save_cookie_to_pool(cookies_dict)
    except Exception as error:
        logger.error('共享 Cookie 池写入失败: %s', error)
        raise RuntimeError(
            '扫码成功，但写入共享 Cookie 池失败。请先运行 script/sql/apply-source-cookie-pool.ps1'
        ) from error

    return cookies_dict


def create_qrcode_login_session(show_qrcode: bool = True) -> dict:
    from source.config import PASSPORT_HEADERS, UA

    logger.info('开始扫码登录流程...')

    session = build_initial_cookies()

    cna = (session.cookies.get('cna', domain='.goofish.com')
           or session.cookies.get('cna', domain='.mmstat.com') or '')
    cookie2 = session.cookies.get('cookie2', domain='.goofish.com') or ''

    logger.info('加载passport登录页面...')
    session.get('https://passport.goofish.com/mini_login.htm',
                params={
                    'lang': 'zh_cn', 'appName': 'xianyu', 'appEntrance': 'web',
                    'styleType': 'vertical', 'bizParams': '',
                    'notLoadSsoView': 'false', 'notKeepLogin': 'false',
                    'isMobile': 'false', 'qrCodeFirst': 'false',
                    'stie': '77', 'rnd': '0.6842814084442211',
                },
                headers={**PASSPORT_HEADERS, 'Referer': 'https://www.goofish.com/',
                         'sec-fetch-site': 'same-site', 'sec-fetch-dest': 'iframe',
                         'sec-fetch-mode': 'navigate'},
                timeout=15)

    csrf_token = session.cookies.get('XSRF-TOKEN', domain='passport.goofish.com') or ''
    logger.info('已获取passport CSRF token')

    gen_params = {
        'appName': 'xianyu', 'fromSite': '77',
        'appEntrance': 'web',
        '_csrf_token': csrf_token,
        'umidToken': '', 'hsiz': cookie2,
        'bizParams': f'taobaoBizLoginFrom=web&renderRefer={quote("https://www.goofish.com/")}',
        'mainPage': 'false', 'isMobile': 'false',
        'lang': 'zh_CN', 'returnUrl': '', 'umidTag': 'SERVER',
    }
    gen_resp = session.get(
        'https://passport.goofish.com/newlogin/qrcode/generate.do',
        params=gen_params,
        headers={**PASSPORT_HEADERS,
                 'Referer': 'https://passport.goofish.com/mini_login.htm'},
        timeout=10).json()

    gen_data = gen_resp['content']['data']
    qr_url = gen_data['codeContent']
    qr_t = gen_data['t']
    qr_ck = gen_data['ck']

    logger.info(f'二维码已生成，链接: {qr_url}')
    print(f'[扫码登录] 二维码链接: {qr_url}')
    print('[扫码登录] 请使用闲鱼APP扫描（左上角 -> 扫一扫）')

    if show_qrcode:
        _print_terminal_qrcode(qr_url)

    return {
        'session': session,
        'cna': cna,
        'cookie2': cookie2,
        'csrf_token': csrf_token,
        'qr_t': qr_t,
        'qr_ck': qr_ck,
        'qr_url': qr_url,
        'user_agent': UA,
    }


def complete_qrcode_login_session(login_context: dict, poll_interval: float = 3.0,
                                  timeout: float = 120.0) -> dict:
    from source.config import PASSPORT_HEADERS, MTOP_HEADERS, COOKIE_FILE

    session = login_context['session']
    cna = login_context['cna']
    cookie2 = login_context['cookie2']
    csrf_token = login_context['csrf_token']
    qr_t = login_context['qr_t']
    qr_ck = login_context['qr_ck']
    user_agent = login_context['user_agent']

    query_url = 'https://passport.goofish.com/newlogin/qrcode/query.do'
    query_base = {
        'appName': 'xianyu', 'fromSite': '77',
        'appEntrance': 'web', '_csrf_token': csrf_token,
        'umidToken': '', 'hsiz': cookie2,
        'bizParams': f'taobaoBizLoginFrom=web&renderRefer={quote("https://www.goofish.com/")}',
        'mainPage': 'false', 'isMobile': 'false',
        'lang': 'zh_CN', 'returnUrl': '', 'umidTag': 'SERVER',
        'navlanguage': 'en', 'navUserAgent': user_agent, 'navPlatform': 'MacIntel',
        'isIframe': 'true',
        'documentReferer': 'https://www.goofish.com/',
        'defaultView': 'sms',
        'deviceId': cna,
    }
    deadline = time.time() + timeout
    login_token = None
    last_status = ''

    logger.info(f'开始轮询扫码状态，超时: {timeout}s')
    while time.time() < deadline:
        body = {**query_base, 't': str(qr_t), 'ck': qr_ck}
        resp = session.post(
            f'{query_url}?appName=xianyu&fromSite=77',
            data=body,
            headers={**PASSPORT_HEADERS,
                     'Content-Type': 'application/x-www-form-urlencoded',
                     'Origin': 'https://passport.goofish.com',
                     'Referer': 'https://passport.goofish.com/mini_login.htm'},
            timeout=10)
        qdata = resp.json()['content']['data']
        status = qdata.get('qrCodeStatus', '')

        if status != last_status:
            remaining = int(deadline - time.time())
            status_map = {
                'NEW': '等待扫码',
                'SCANNED': '已扫码，请在手机上确认',
                'CONFIRMED': '已确认',
                'EXPIRED': '二维码已过期',
            }
            desc = status_map.get(status, status)
            logger.info(f'扫码状态变更: {last_status} -> {status} ({desc})')
            print(f'[扫码登录] [{status}] {desc} (剩余{remaining}秒)')
            last_status = status

        if status == 'CONFIRMED':
            login_token = qdata.get('token') or qdata.get('lgToken')
            logger.info('扫码已确认，获取到login_token')
            break
        if status == 'EXPIRED':
            logger.error('二维码已过期')
            raise TimeoutError('二维码已过期，请重新调用登录')

        time.sleep(poll_interval)

    if not login_token:
        if session.cookies.get('unb'):
            logger.info('登录成功（通过 Set-Cookie）')
            print('[扫码登录] 登录成功（通过 Set-Cookie）')
        else:
            logger.error('扫码超时，未完成登录')
            raise TimeoutError('扫码超时，未完成登录')
    else:
        logger.info('正在完成login_token登录...')
        login_resp = session.post(
            'https://passport.goofish.com/login_token/login.do',
            params={'token': login_token, 'subFlow': 'DIALOG_CHECK_LOGIN_RPC',
                    'nextCode': '0018', 'bizScene': 'qrcode', 'confirm': 'true'},
            data={'deviceId': cna},
            headers={**PASSPORT_HEADERS,
                     'Content-Type': 'application/x-www-form-urlencoded',
                     'Origin': 'https://passport.goofish.com',
                     'Referer': 'https://passport.goofish.com/mini_login.htm'},
            timeout=10)
        logger.info(f'login_token请求完成, status: {login_resp.status_code}')
        print(f'[扫码登录] login_token 请求完成, status: {login_resp.status_code}')

    logger.info('正在刷新mtop cookie...')
    session.post(
        'https://h5api.m.goofish.com/h5/mtop.idle.web.user.page.nav/1.0/',
        params={
            'jsv': '2.7.2', 'appKey': '34839810',
            't': str(int(time.time() * 1000)), 'sign': '', 'v': '1.0',
            'type': 'originaljson', 'dataType': 'json', 'timeout': '20000',
            'api': 'mtop.idle.web.user.page.nav',
            'sessionOption': 'AutoLoginOnly', 'spm_cnt': 'a21ybx.home.0.0',
        },
        data='data=%7B%7D', headers=MTOP_HEADERS, timeout=10)

    unb = session.cookies.get('unb', domain='.goofish.com') or ''
    tracknick = session.cookies.get('tracknick', domain='.goofish.com') or ''
    logger.info(f'登录成功! 用户: {tracknick} (unb={unb})')
    print(f'[扫码登录] 登录成功! 用户: {tracknick} (unb={unb})')

    cookies_dict = _persist_login_cookies(session, COOKIE_FILE)
    cookies_dict['unb'] = unb or cookies_dict.get('unb')
    return cookies_dict


def qrcode_login(poll_interval: float = 3.0, timeout: float = 120.0,
                 show_qrcode: bool = True) -> dict:
    """
    扫码登录闲鱼，返回Cookie字典
    """
    login_context = create_qrcode_login_session(show_qrcode=show_qrcode)
    return complete_qrcode_login_session(
        login_context,
        poll_interval=poll_interval,
        timeout=timeout,
    )


def load_cookie_from_file() -> dict:
    """从文件加载Cookie"""
    from source.config import COOKIE_FILE
    if not os.path.exists(COOKIE_FILE):
        logger.warning(f'Cookie文件不存在: {COOKIE_FILE}')
        return {}
    with open(COOKIE_FILE, 'r', encoding='utf-8') as f:
        cookies = json.load(f)
    logger.info(f'已从文件加载Cookie: {COOKIE_FILE} ({len(cookies)} 个字段)')
    return cookies
