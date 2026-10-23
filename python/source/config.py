"""
货源管理模块 - 独立配置文件
包含 source 模块运行所需的全部配置常量，无需依赖外部 config.py

使用方式：
  from source.config import SOURCE_MYSQL_CONFIG, ROWS_PER_PAGE
"""

import os
import logging

# ============================================================
# 日志配置
# ============================================================
LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'logs')
os.makedirs(LOG_DIR, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s - %(message)s',
    handlers=[
        logging.FileHandler(os.path.join(LOG_DIR, 'source.log'), encoding='utf-8'),
        logging.StreamHandler(),
    ],
)
logger = logging.getLogger('goofish.source')

# ============================================================
# 数据库配置 - 货源管理模块（阿里云 RDS MySQL 8.x）
# ============================================================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

SOURCE_MYSQL_CONFIG = {
    'host': os.getenv('SOURCE_DB_HOST', '127.0.0.1'),
    'port': int(os.getenv('SOURCE_DB_PORT', '3306')),
    'user': os.getenv('SOURCE_DB_USER', 'root'),
    'password': os.getenv('SOURCE_DB_PASSWORD', ''),
    'database': os.getenv('SOURCE_DB_NAME', 'xianyuops'),
    'charset': 'utf8mb4',
    'autocommit': False,
    'ssl_disabled': True,
    'connect_timeout': 10,
    'read_timeout': 30,
    'write_timeout': 30,
    'init_command': "SET time_zone = '+08:00'",
}

# ============================================================
# 闲鱼 API 配置
# ============================================================
SEARCH_API_URL = "https://h5api.m.goofish.com/h5/mtop.taobao.idlemtopsearch.pc.search/1.0/"
SEARCH_API_NAME = "mtop.taobao.idlemtopsearch.pc.search"

ITEM_DETAIL_API_URL = "https://h5api.m.goofish.com/h5/mtop.taobao.idle.pc.detail/1.0/"
ITEM_DETAIL_API_NAME = "mtop.taobao.idle.pc.detail"

APP_KEY = "34839810"
ROWS_PER_PAGE = 30

# ============================================================
# 请求配置
# ============================================================
REQUEST_INTERVAL = 3.0          # 搜索翻页间隔（秒）
REQUEST_TIMEOUT = 20            # 请求超时（秒）
MAX_PAGES = 20                  # 最大搜索页数
SOURCE_SEARCH_MAX_PAGES = 50    # 货源搜索安全页数上限

# 货源模块详情采集间隔（秒）
SOURCE_DETAIL_INTERVAL_MIN = 5.0
SOURCE_DETAIL_INTERVAL_MAX = 8.0
SOURCE_DETAIL_JITTER = 2.0

# RGV587 指数退避配置
SOURCE_BACKOFF_INITIAL = 10.0
SOURCE_BACKOFF_MULTIPLIER = 2.0
SOURCE_BACKOFF_MAX = 120.0
SOURCE_BACKOFF_BATCH_PAUSE = 60.0
SOURCE_BACKOFF_BATCH_THRESHOLD = 5

# ============================================================
# DeepSeek LLM 配置
# ============================================================
DEEPSEEK_BASE_URL = os.getenv('DEEPSEEK_BASE_URL', 'https://api.deepseek.com')
DEEPSEEK_API_KEY = os.getenv('DEEPSEEK_API_KEY', '')
DEEPSEEK_MODEL = os.getenv('DEEPSEEK_MODEL', 'deepseek-chat')
DEEPSEEK_BATCH_SIZE = 10          # 每批分析的商品数
DEEPSEEK_TIMEOUT = 60             # 请求超时(秒)

# ============================================================
# Cookie 存储路径
# ============================================================
# 优先使用 source 目录下的 cookie.json，回退到项目根目录
COOKIE_FILE = os.path.join(BASE_DIR, "cookie.json")
if not os.path.exists(COOKIE_FILE):
    _ROOT_COOKIE = os.path.join(os.path.dirname(BASE_DIR), "cookie.json")
    if os.path.exists(_ROOT_COOKIE):
        COOKIE_FILE = _ROOT_COOKIE

# ============================================================
# 请求 Headers
# ============================================================
UA = ('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
      'AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/147.0.0.0 Safari/537.36')

MTOP_HEADERS = {
    'User-Agent':         UA,
    'Accept':             'application/json',
    'Accept-Language':    'zh-CN,zh;q=0.9,en;q=0.8',
    'sec-ch-ua':          '"Google Chrome";v="147", "Not.A/Brand";v="8", "Chromium";v="147"',
    'sec-ch-ua-mobile':   '?0',
    'sec-ch-ua-platform': '"macOS"',
    'Origin':             'https://www.goofish.com',
    'Referer':            'https://www.goofish.com/',
    'sec-fetch-dest':     'empty',
    'sec-fetch-mode':     'cors',
    'sec-fetch-site':     'same-site',
    'priority':           'u=1, i',
    'Content-Type':       'application/x-www-form-urlencoded',
}

PASSPORT_HEADERS = {
    'User-Agent':         UA,
    'Accept':             'application/json, text/plain, */*',
    'Accept-Language':    'zh-CN,zh;q=0.9,en;q=0.8',
    'sec-ch-ua':          '"Google Chrome";v="147", "Not.A/Brand";v="8", "Chromium";v="147"',
    'sec-ch-ua-mobile':   '?0',
    'sec-ch-ua-platform': '"macOS"',
    'sec-fetch-dest':     'empty',
    'sec-fetch-mode':     'cors',
    'sec-fetch-site':     'same-origin',
    'priority':           'u=1, i',
}
