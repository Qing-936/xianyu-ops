"""闲鱼号 + Cookie 供应链（10 号文档 M1 步骤 3；09 §2.4 实测结论）。

主源 / 降级：
- 主源：`accounts.json` 缓存文件（调试口导出的产物，Cookie 2~3 天有效，需配有效期监控）
- 降级：文件缺失/为空时，连闲管家调试口 9401 导出并写回缓存。
  ⚠️ 调试口（单横线）会破坏闲管家加密层（首页数据加载不出来）且自身短时不可用 ~2min，
  只作临时手段，导出后必须正常重启闲管家。

移植自 reference-projects/xianyu-webim/src/account_store.py，仅改包导入。
"""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime

from agent.channel.webim.cdp import CDP, http_json, pick_target


# 只使用页面里的全局存储对象，不 require 任何原生模块。
DUMP_JS = r"""
(function () {
  var s = window._xgJDbStoreInstance;
  if (!s) { return JSON.stringify({ error: 'store-not-found' }); }
  var out = { accounts: [] };
  var authList = s.getKey('authList');
  if (typeof authList === 'string') { try { authList = JSON.parse(authList); } catch (e) { authList = []; } }
  (authList || []).forEach(function (a) {
    var uid = String(a.user_id);
    var cookies = s.getKey('xy_ck2_' + uid);
    if (typeof cookies === 'string') { try { cookies = JSON.parse(cookies); } catch (e) { cookies = []; } }
    out.accounts.push({
      user_id: uid,
      shop_name: a.shop_name || a.user_nick || '',
      webview_src: a.webviewSrc || ('https://www.goofish.com/im?appEntrance=web&id=' + uid),
      cookies: cookies || []
    });
  });
  return JSON.stringify(out);
})()
"""


def export_accounts(port: int) -> list[dict]:
    """连接闲管家调试端口，返回账号列表（含 Cookie）。"""
    targets = http_json(f"http://127.0.0.1:{port}/json/list")
    target = pick_target(targets, "app://")          # 任一 app:// 页面都有该全局对象
    client = CDP(target["webSocketDebuggerUrl"])
    try:
        raw = client.evaluate(DUMP_JS)
    finally:
        client.close()
    if isinstance(raw, str):
        raw = json.loads(raw)
    if raw.get("error"):
        raise RuntimeError(f"export failed: {raw['error']}")
    accounts = raw.get("accounts", [])
    for account in accounts:
        account["cookie_count"] = len(account.get("cookies") or [])
    return accounts


def load_accounts_file(path: str) -> list[dict]:
    """读 accounts.json 缓存（Cookie 主源）；文件不存在返回 []。"""
    if not path or not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def resolve_accounts(accounts_file: str, export_port: int, log=None) -> list[dict]:
    """主源→降级：缓存文件非空直接读；否则调试口导出并写回缓存。

    返回账号列表；降级导出失败（闲管家未开调试口）抛 RuntimeError。
    """
    accounts = load_accounts_file(accounts_file)
    if accounts:
        return accounts
    if log:
        log.warning("accounts.json 缺失/为空，降级尝试调试口 %d 导出（导出后须正常重启闲管家）", export_port)
    accounts = export_accounts(export_port)
    if accounts_file:
        parent = os.path.dirname(os.path.abspath(accounts_file))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(accounts_file, "w", encoding="utf-8") as fh:
            json.dump(accounts, fh, ensure_ascii=False, indent=1)
        if log:
            log.info("已导出 %d 个账号并写回 %s", len(accounts), accounts_file)
    return accounts


def main() -> int:
    parser = argparse.ArgumentParser(description="从闲管家导出闲鱼号与 Cookie")
    parser.add_argument("--port", type=int, default=9401, help="闲管家调试端口（默认 9401）")
    parser.add_argument("--out", default="accounts.json", help="输出文件（默认 accounts.json）")
    args = parser.parse_args()

    accounts = export_accounts(args.port)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(accounts, fh, ensure_ascii=False, indent=1)

    print(f"wrote {args.out}: {len(accounts)} account(s) at {datetime.now():%Y-%m-%d %H:%M:%S}")
    for account in accounts:
        print(f"  - {account['shop_name']} (user_id={account['user_id']}, "
              f"cookies={account.get('cookie_count', 0)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
