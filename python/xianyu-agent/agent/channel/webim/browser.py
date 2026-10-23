"""闲鱼 Web IM 自动化封装（浏览器 + Cookie + UA 伪装 + DOM 读写）。

移植自 reference-projects/xianyu-webim/src/webim.py（10 号文档 M1），改动：
1. `sys.path` hack → 包内导入 `agent.channel.webim.cdp`
2. stage0 增强合并进 DOM JS（09 号文档实测证据），分两层，✅ 已按 vm101 真实 DOM 校准（M1 实测）：
   - 消息行级（MESSAGES_JS）：气泡方向（message-text-left/right--）+ 时间戳三格式
     （MM-DD HH:mm / 昨天|前天 HH:mm / HH:mm，DOM 顺序事件流，Python 侧时间锚点向下传播）
     + 商品卡 item_id/价格（类名哈希后缀 → 前缀匹配）
   - 会话行级（COLLECT_JS）：昵称/未读角标（ant-badge sup）/相对时间/预览 结构化提取 + raw 兜底
3. 踩坑吸收（P2-4，09 §3 + M1 实测）：
   - `AccountPage.open()` 页面就绪等待不沿用参考实现 20s——vm101 2 vCPU 软渲染实测
     40~60s，默认 60s × 3 次重试；blocked（UA 未生效）立即失败不重试
   - 浏览器启动前/关闭后按 CommandLine 清理 `.xianyu-webim` 残留 msedge 进程树
     （残留进程锁死 profile 致调试口未就绪）
   - `.rc-virtual-list-holder` 页面加载后短暂消失（列表重建窗口）→ holder 查询轮询等待 + 二次确认；
   - `scrollTop=0` 直接赋值会被 React 内部状态恢复 → 改用增量双向扫描（先 -= 到顶再 += 到底）；
   - 缓存 holder 引用可能 detach（列表重建替换节点）→ tick 每步重查 + isConnected 校验
4. 时间解析规则（P2-5 + m1f 实测）：`MM-DD HH:mm` 无年份 → 月份大于当前月判上一年；
   `昨天/前天 HH:mm`、当天 `HH:mm` 相对解析
"""
from __future__ import annotations

import json
import os
import platform
import re
import socket
import subprocess
import time
from datetime import datetime, time as dtime, timedelta

from agent.channel.webim.cdp import CDP, find_browser, http_json, wait_port

# 闲管家内置浏览器 UA —— 不加这个会返回"非法访问，请使用正常浏览器访问闲鱼"
APP_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          "goofish-im/1.21.88 Chrome/108.0.5359.215 Electron/22.3.23 Safari/537.36")

_TIME_RE = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2})$")
_REL_TIME_RE = re.compile(r"^(昨天|前天) (\d{1,2}):(\d{2})$")
_HHMM_RE = re.compile(r"^(\d{1,2}):(\d{2})$")


def parse_msg_time(text: str, now: datetime | None = None) -> datetime | None:
    """消息时间戳三格式 → datetime：

    - `MM-DD HH:mm`（无年份，月份大于当前月判上一年，P2-5 跨年规则）
    - `昨天/前天 HH:mm`（vm101 m1f 实测）
    - `HH:mm`（当天，vm101 m1f 实测）
    """
    t = (text or "").strip()
    now = now or datetime.now()
    m = _TIME_RE.match(t)
    if m:
        month, day, hour, minute = (int(g) for g in m.groups())
        year = now.year
        if month > now.month:  # 跨年：如当前 01 月读到 12-31 → 上一年
            year -= 1
        try:
            return datetime(year, month, day, hour, minute)
        except ValueError:
            return None
    m = _REL_TIME_RE.match(t)
    if m:
        day = now.date() - timedelta(days=1 if m.group(1) == "昨天" else 2)
        try:
            return datetime.combine(day, dtime(int(m.group(2)), int(m.group(3))))
        except ValueError:
            return None
    m = _HHMM_RE.match(t)
    if m:
        try:
            return datetime.combine(now.date(), dtime(int(m.group(1)), int(m.group(2))))
        except ValueError:
            return None
    return None


def attach_times(events: list[dict]) -> list[dict]:
    """时间锚点向下传播：消息事件取其前最近一个 time 事件（与 ui_ocr _read_chat_lines 同思路）。

    13 号文档 M1-1：kind=card 与 msg 同等传播（卡片并入事件流获得 receive_time）。
    """
    out, cur = [], None
    for e in events:
        if e.get("kind") == "time":
            cur = e.get("text")
        elif e.get("kind") in ("msg", "card", "media"):
            msg = dict(e)
            msg["time_text"] = cur
            out.append(msg)
    return out


def _kill_profile_processes(profile_dir: str) -> int:
    """按 CommandLine 清理残留 msedge/chrome 进程树（阶段 0 踩坑：残留进程锁死 profile）。"""
    killed = 0
    try:
        import psutil
    except ImportError:
        return 0
    p = os.path.abspath(profile_dir)
    markers = {p, p.replace("\\", "/")}  # 双形态匹配（Windows CommandLine 正反斜杠均出现）
    for proc in psutil.process_iter(["name", "cmdline"]):
        try:
            name = (proc.info.get("name") or "").lower()
            if name not in ("msedge.exe", "chrome.exe"):
                continue
            cmd = " ".join(proc.info.get("cmdline") or [])
            if not any(m in cmd for m in markers):
                continue
            try:
                for child in reversed(proc.children(recursive=True)):
                    try:
                        child.kill()
                    except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
                        pass
                proc.kill()
                killed += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
                continue
        except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
            continue
    return killed


# --------------------------------------------------------------------------- #
# 页面探测 / 操作脚本（均已在本项目实测；消息/会话两级已按 09 号文档 stage0 增强）
# --------------------------------------------------------------------------- #
READY_JS = r"""
(function () {
  var text = (document.body && document.body.innerText) || '';
  var reconnected = false;
  // 闲鱼 IM WS 断开会弹「连接中断 ，请重连」模态弹窗，挡住列表且 hasList 一直不渲染
  // （vm101 2026-09-22 实测）。每次探测都尝试点击「重 连」自愈——点击后弹窗关闭、
  // 列表恢复渲染；若 WS 未恢复闲鱼会再弹，下轮探测继续点，由上层超时兜底。
  var disconnected = text.indexOf('连接中断') >= 0 || text.indexOf('请重连') >= 0;
  if (disconnected) {
    var nodes = document.querySelectorAll('button, [role="button"], div, span');
    for (var i = 0; i < nodes.length; i++) {
      var t = ((nodes[i].innerText || '') + '').replace(/\s+/g, '');
      if (t === '重连') {
        try { nodes[i].click(); reconnected = true; } catch (e) {}
        break;
      }
    }
  }
  return JSON.stringify({
    url: location.href,
    title: document.title,
    hasList: !!document.querySelector('.rc-virtual-list-holder'),
    blocked: text.indexOf('非法访问') >= 0,
    needLogin: /\/login|请登录|扫码登录/.test(text),
    disconnected: disconnected,
    reconnected: reconnected
  });
})()
"""

# 会话行级（P2-1）：昵称 / 未读角标 / 相对时间 / 预览 结构化提取。
# ✅ 已按 vm101 真实 DOM 校准（2026-09-22 M1 实测）：
#   - 会话行 = conversation-item--{hash} > ... > [头像列 span.ant-badge] + [文本列：昵称 div(14px 加粗)
#     → 预览 div(12px) → 时间 div(10px)] + [右侧商品图 img]；
#   - 文本列有 4 个子元素，叶子提取必须全递归（children<=3 条件会跳过整列）；
#   - 未读角标 = ant-badge 内 sup（ant-scroll-number）；
#   - 页面加载后 .rc-virtual-list-holder 短暂消失（列表重建窗口），holder 查询需轮询等待。
COLLECT_JS = r"""
(function () {
  return new Promise(function (resolve) {
    // vm101 实测：页面加载后 .rc-virtual-list-holder 会短暂消失（React 列表重建窗口），
    // 直接查询会偶发 no-list-holder；轮询等待且出现后再二次确认（防重建窗口再次消失）。
    var tries = 0;
    (function waitHolder() {
      var holder = document.querySelector('.rc-virtual-list-holder');
      if (holder) {
        setTimeout(function () {
          var h2 = document.querySelector('.rc-virtual-list-holder');
          if (h2) { run(h2); return; }
          if (++tries > 20) { resolve(JSON.stringify({ error: 'no-list-holder' })); return; }
          setTimeout(waitHolder, 500);
        }, 1000);
        return;
      }
      if (++tries > 20) { resolve(JSON.stringify({ error: 'no-list-holder' })); return; }
      setTimeout(waitHolder, 500);
    })();
    function run(holder) {
    var seen = {}, rows = [], steps = 0, phase = 'up';
    (function tick() {
      // vm101 实测：列表重建会替换 holder 节点，缓存的引用可能已 detach；
      // 每步重查，无效则等下一拍（不推进 step 不滚动）
      var holder = document.querySelector('.rc-virtual-list-holder');
      if (!holder || !holder.isConnected) { setTimeout(tick, 600); return; }
      Array.prototype.slice.call(document.querySelectorAll('div[class*="conversation-item--"]'))
        .forEach(function (row) {
          var raw = (row.innerText || '').replace(/\s+/g, ' ').trim();
          if (!raw || seen[raw]) return;
          // P0-1（12 号文档，vm101 探针实锤）：骨架屏竞态守卫 + 结构法昵称提取。
          // 虚拟列表行重建窗口期昵称 div（内联样式 font-size:14px; font-weight:500）
          // display:none 且文本为空（ant-skeleton 占位），此时 parts[0] 落到消息预览
          // → 每条新消息制造一个「假昵称会话」，各耗 3 次打开尝试 25~50s（buyer_missing 根因）。
          // 守卫：昵称节点已渲染出文本才采集该行（未就绪不标 seen，下一步渲染完成后正常采）；
          // 已渲染则结构法直取昵称（M1 校准：昵称 div 14px 加粗），节点缺失时退位置法。
          var nickEl = null;
          var rowDivs = row.querySelectorAll('div');
          for (var k = 0; k < rowDivs.length; k++) {
            var st = rowDivs[k].getAttribute('style') || '';
            if (st.indexOf('font-size: 14px') >= 0 && st.indexOf('font-weight: 500') >= 0) { nickEl = rowDivs[k]; break; }
          }
          var nickText = '';
          if (nickEl) {
            // 昵称节点内可能追加订单状态标签子 div（order-close/order-success，9px，vm101 探针实锤）：
            // innerText 会拼进「交易成功」等后缀致假昵称 → 结构法取第一个子元素（昵称本体，
            // ellipsis 样式）；无子元素时取自身文本。仍为空 = 骨架屏未渲染，跳过本行。
            var c0 = nickEl.children && nickEl.children.length ? nickEl.children[0] : nickEl;
            nickText = (c0.innerText || '').replace(/\s+/g, ' ').trim();
            if (!nickText) nickText = (nickEl.innerText || '').replace(/\s+/g, ' ').trim();
            if (!nickText) return;
          }
          seen[raw] = 1;
          // 未读角标（vm101 实测）：头像列是 antd Badge（span.ant-badge），
          // 有未读时内部渲染 sup（ant-scroll-number）数字；无未读时 sup 不存在。
          var unreadText = '';
          var badgeEl = row.querySelector('.ant-badge sup, .ant-scroll-number');
          if (badgeEl) unreadText = (badgeEl.innerText || '').trim();
          if (!unreadText) {
            Array.prototype.slice.call(row.querySelectorAll('[class*="unread"], [class*="badge"], [class*="dot"]'))
              .some(function (el) {
                var t = (el.innerText || '').trim();
                if (t) { unreadText = t; return true; }
                return false;
              });
          }
          // 叶子块全递归（vm101 实测：文本列有 4 个子元素，原 children<=3 条件会跳过
          // 整列导致 parts 全空；昵称/预览/时间均为无子元素的 div，按 DOM 顺序收集即可）
          var leaves = [];
          (function walk(el) {
            Array.prototype.slice.call(el.children).forEach(function (c) {
              if (c.closest && c.closest('.ant-badge')) return;  // 头像徽标子树（含 sup 角标）不进 parts
              if (!c.children.length) {
                var t = (c.innerText || '').replace(/\s+/g, ' ').trim();
                if (t && t.length <= 40) leaves.push(t);
              } else { walk(c); }
            });
          })(row);
          var parts = [];
          leaves.forEach(function (t) { if (parts[parts.length - 1] !== t) parts.push(t); });
          // 相对时间：最后一个含「前/昨天/时分」的叶子块
          var timeText = '';
          for (var j = parts.length - 1; j >= 0; j--) {
            if (/前|昨天|\d{1,2}:\d{2}/.test(parts[j])) { timeText = parts[j]; break; }
          }
          // 会话行商品缩略图（会话级商品快照，管理端左栏/头部展示）：
          // vm101 探针实锤行内固定两图——买家头像（span.ant-badge 子树，含未读角标）+
          // 行右侧商品缩略图；另有订单状态小图标（<24px）。排除前两者，取最后一个候选。
          var productImg = '';
          (function () {
            var cands = [];
            Array.prototype.slice.call(row.querySelectorAll('img')).forEach(function (im) {
              if (im.closest && im.closest('.ant-badge')) return;
              var rr = im.getBoundingClientRect();
              if (rr.width < 24) return;
              var s = im.getAttribute('data-actualsrc') || im.getAttribute('data-src')
                    || im.currentSrc || im.src || im.getAttribute('src') || '';
              if (s) cands.push(s);
            });
            if (cands.length) productImg = cands[cands.length - 1];
          })();
          rows.push({
            raw: raw,
            parts: parts.slice(0, 6),
            nick: nickText || parts[0] || '',
            time_text: timeText,
            unread_text: unreadText,
            unread_count: /^\d+$/.test(unreadText) ? parseInt(unreadText, 10) : null,
            product_img: productImg
          });
        });
      if (++steps > %(steps)d) { resolve(JSON.stringify({ rows: rows })); return; }
      // vm101 实测：scrollTop=0 直接赋值会被 React 内部状态恢复（滚动位置不变）；
      // 改用增量双向扫描：先向上（-=）到顶，再向下（+=）到底。
      var before = holder.scrollTop;
      if (phase === 'up') {
        holder.scrollTop -= holder.clientHeight * 0.8;
        if (holder.scrollTop >= before - 1) { phase = 'down'; }  // 不再变小 = 已到顶
      } else {
        holder.scrollTop += holder.clientHeight * 0.8;
        if (holder.scrollTop <= before + 1) { resolve(JSON.stringify({ rows: rows })); return; }  // 到底
      }
      setTimeout(tick, 600);
    })();
    }
  });
})()
"""

OPEN_JS = r"""
(function () {
  return new Promise(function (resolve) {
    var NICK = %(nick)s;
    // 会话唯一 id（2026-09-24 会话唯一性修复）：非空时优先按 sessionId 精确定位行——
    // 同一买家对多个商品的会话昵称完全相同，仅按昵称只能点到第一行（读/发均会串会话）
    var SID = %(sid)s;
    function fiberSessionId(row) {
      try {
        var ks = Object.keys(row), f = null;
        for (var i = 0; i < ks.length; i++) {
          if (ks[i].indexOf('__reactFiber$') === 0) { f = row[ks[i]]; break; }
        }
        var depth = 0;
        while (f && depth < 8) {
          var p = f.memoizedProps;
          if (p && p.sessionInfo && p.sessionInfo.sessionId) return String(p.sessionInfo.sessionId);
          f = f.return; depth++;
        }
      } catch (e) {}
      return '';
    }
    // 同 COLLECT_JS：holder 短暂消失窗口轮询 + 二次确认
    var tries = 0;
    (function waitHolder() {
      var holder = document.querySelector('.rc-virtual-list-holder');
      if (holder) {
        setTimeout(function () {
          var h2 = document.querySelector('.rc-virtual-list-holder');
          if (h2) { run(h2); return; }
          if (++tries > 20) { resolve('no-list-holder'); return; }
          setTimeout(waitHolder, 500);
        }, 1000);
        return;
      }
      if (++tries > 20) { resolve('no-list-holder'); return; }
      setTimeout(waitHolder, 500);
    })();
    function run(holder) {
    var step = 0, phase = 'up';
    (function tick() {
      // 同 COLLECT_JS：holder 每步重查防 detach
      var holder = document.querySelector('.rc-virtual-list-holder');
      if (!holder || !holder.isConnected) { setTimeout(tick, 600); return; }
      // P1-4：skip>0 时点第 skip+1 个命中行（多行同名买家逐行验证用）
      // P0-1 补刀（vm101 实测）：比对前同样折叠空白——采集出的 NICK 已把行内换行折叠成空格，
      // 而行原始 innerText 昵称/标签间可能带 \n（昵称文本被拆分/emoji 图分隔），不折叠必漏配
      var candRows = Array.prototype.slice.call(document.querySelectorAll('div[class*="conversation-item--"]'));
      var hit = null;
      if (SID) {
        // sessionId 精确路径（2026-09-28 修复）：只按会话唯一 id 定位，绝不按昵称降级——
        // 同一买家多条商品会话昵称完全相同，降级点兄弟会话会被 activeCid 闸门全拒 → 误熔断
        // （vm101「是竹子啊」实测：目标 sid 未渲染时点了 3 条兄弟会话全被拒，熔断 30min）
        for (var hi = 0; hi < candRows.length; hi++) {
          if (fiberSessionId(candRows[hi]) === SID) { hit = candRows[hi]; break; }
        }
      } else {
        // 无 sid（发送侧 / 旧行为）：按昵称逐行验证，skip>0 点第 skip+1 个命中行
        // P0-1 补刀（vm101 实测）：比对前折叠空白——采集出的 NICK 已把行内换行折叠成空格，
        // 而行原始 innerText 昵称/标签间可能带 \n，不折叠必漏配
        hit = candRows.filter(function (row) {
          return (row.innerText || '').replace(/\s+/g, ' ').indexOf(NICK) >= 0;
        })[%(skip)d] || null;
      }
      if (hit) {
        ['mouseover', 'mousedown', 'mouseup', 'click'].forEach(function (type) {
          hit.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
        });
        try { hit.click(); } catch (e) {}
        resolve('clicked: ' + (hit.innerText || '').replace(/\s+/g, ' ').slice(0, 60));
        return;
      }
      // SID 路径耗尽步数仍未定位目标行 → 独立信号 sid-not-found（上层不降级、不计熔断 strike）
      if (++step > %(max_steps)d) { resolve(SID ? 'sid-not-found' : 'not-found'); return; }
      // 同 COLLECT_JS：增量双向扫描（先向上到顶，再向下），scrollTop=0 赋值会被 React 恢复
      var before = holder.scrollTop;
      if (phase === 'up') {
        holder.scrollTop -= holder.clientHeight * 0.6;
        if (holder.scrollTop >= before - 1) { phase = 'down'; }
      } else {
        holder.scrollTop += holder.clientHeight * 0.6;
      }
      setTimeout(tick, 600);
    })();
    }
  });
})()
"""

# 消息行级（stage0 增强）：一次 DOM 顺序遍历，产出「时间分隔条 + 气泡 + 商品卡」事件流
# （时间锚点向下传播在 Python 侧 attach_times 完成，卡片与气泡同序获得 receive_time）。
# 09 §2.1/§2.2 实测：时间戳为叶子文本 MM-DD HH:mm；类名带构建哈希后缀须前缀匹配。
# 13 号 §2.2 探针校准 + §七.3 兜底增强：卡片方向类挂消息行（left--/right-- 前缀），卡片容器无方向类；
# 主图优先读 currentSrc/src（补全 https scheme、懒加载取真图，src 常为占位/协议相对地址）。
# 商品卡 DOM 经 vm101 探针实锤只含 图+价格+地区，无标题文本节点、img alt 空 → title 恒空由前端「商品#itemId」兜底。
MESSAGES_JS = r"""
(function () {
  var events = [], handledRoots = new WeakSet(), seenMessages = new Set();
  function messageProps(el) {
    var key = Object.keys(el).find(function (k) { return k.indexOf('__reactFiber$') === 0; });
    var fiber = key ? el[key] : null;
    for (var depth = 0; fiber && depth < 16; depth++, fiber = fiber.return) {
      var p = fiber.memoizedProps;
      if (p && p.message && p.message.content && typeof p.isMyMsg === 'boolean') return p;
    }
    return null;
  }
  function imageSrc(img) {
    return img ? img.getAttribute('data-actualsrc') || img.getAttribute('data-src')
      || img.currentSrc || img.getAttribute('src') || '' : '';
  }
  function direction(el, props) {
    if (props) return props.isMyMsg ? 'out' : 'in';
    var row = el.closest('[class*="left--"], [class*="right--"]');
    return row ? (/right--/.test(String(row.className)) ? 'out' : 'in') : '?';
  }
  function emitMedia(el, props, type, items, text) {
    var message = props && props.message;
    var key = message && (message.messageId || message);
    handledRoots.add(el);
    if (key && seenMessages.has(key)) return;
    if (key) seenMessages.add(key);
    events.push({kind: 'media', dir: direction(el, props), content_type: type,
      text: text || '', source_message_id: message && message.messageId,
      timestamp: message && message.timeStamp, items: items});
  }
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
  var node;
  while (node = walker.nextNode()) {
    var el = node, parent = el.parentElement;
    if (parent && handledRoots.has(parent)) { handledRoots.add(el); continue; }
    var cls = typeof el.className === 'string' ? el.className : '';
    var props = messageProps(el), content = props && props.message.content;
    var type = content ? Number(content.contentType) : 0;
    if (type >= 2 && type <= 5) {
      var mediaType = {2: 'image', 3: 'audio', 4: 'video', 5: 'emoji'}[type];
      var items = [];
      if (type === 2) {
        var pics = content.image && content.image.pics;
        if (Array.isArray(pics)) pics.slice(0, 20).forEach(function (pic) {
          if (pic && typeof pic === 'object') items.push({type: 'image', url: pic.url});
        });
      } else if (type === 3) {
        var audio = content.audio || {};
        items.push({type: 'audio', url: audio.url, duration: audio.duration});
      } else if (type === 4) {
        var video = content.video || {};
        items.push({type: 'video', url: video.url});
      } else {
        items.push({type: 'emoji', alt: content.expression && content.expression.name,
          unavailableReason: '闲鱼网页未提供该表情图片，请到闲鱼 App 查看'});
      }
      if (!items.length) items.push({type: mediaType});
      emitMedia(el, props, mediaType, items, '');
      continue;
    }
    if (cls.indexOf('message-text--') >= 0) {
      var emojiItems = Array.prototype.slice.call(el.querySelectorAll('img[alt]'))
        .filter(function (img) { return /^\[.+\]$/.test(img.getAttribute('alt') || ''); })
        .slice(0, 20).map(function (img) { return {type: 'emoji', url: imageSrc(img), alt: img.getAttribute('alt')}; });
      var text = content && content.text && typeof content.text.text === 'string'
        ? content.text.text : (el.innerText || '');
      text = text.replace(/\s+/g, ' ').trim().slice(0, 5000);
      if (emojiItems.length) {
        emitMedia(el, props, 'emoji', emojiItems, text);
      } else {
        events.push({kind: 'msg', dir: direction(el, props), text: text});
        handledRoots.add(el);
      }
    } else if (/image-container--|voice-container--|video-container--/.test(cls)) {
      var fallbackType = /voice-container--/.test(cls) ? 'audio' : (/video-container--/.test(cls) ? 'video' : 'image');
      var mediaEl = el.querySelector('video');
      emitMedia(el, null, fallbackType, [{type: fallbackType,
        url: fallbackType === 'image' ? imageSrc(el.querySelector('img'))
          : (fallbackType === 'video' && mediaEl ? mediaEl.currentSrc || mediaEl.getAttribute('src') : '')}], '');
    } else if (el.tagName === 'A') {
      // 商品卡：DOM 顺序并入事件流（与气泡同序）；方向类取消息行最近祖先的
      // left--/right-- 前缀（无方向类 → '?'，Python 侧按 in 处理——放行方向安全）
      var href = el.getAttribute('href') || '';
      if (href.indexOf('/item?id=') >= 0) {
        var itemId = (href.match(/item\?id=(\d+)/) || [])[1] || '';
        var container = el.closest('div[class*="container--"]') || el.parentElement;
        var priceEl = container && (container.querySelector('[class*="priceTop--"]') || container.querySelector('[class*="price--"]'));
        var deliveryEl = container && container.querySelector('[class*="delivery--"]');
        var imgEl = container && container.querySelector('img');
        // 真实主图：闲鱼图片懒加载，src 常是 2×2 占位（tps-2-2.png）；优先 data-actualsrc/
        // data-src（真实地址），退回 currentSrc（已加载资源）/src（属性）。13 号 §七.3 兜底增强。
        var imgSrc = '';
        if (imgEl) {
          imgSrc = imgEl.getAttribute('data-actualsrc') || imgEl.getAttribute('data-src')
                || imgEl.currentSrc || imgEl.src || imgEl.getAttribute('src') || '';
        }
        var rowEl = el.closest('div[class*="left--"], div[class*="right--"]');
        events.push({
          kind: 'card',
          dir: rowEl ? (/right/i.test(String(rowEl.className)) ? 'out' : 'in') : '?',
          item_id: itemId,
          price: priceEl ? (priceEl.innerText || '').replace(/\s+/g, ' ').trim() : '',
          delivery: deliveryEl ? (deliveryEl.innerText || '').replace(/\s+/g, ' ').trim() : '',
          image_url: imgSrc,
          url: href,
          raw: container ? (container.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 200) : ''
        });
      }
    } else if (!el.children.length) {
      // 时间戳三格式（vm101 实测）：MM-DD HH:mm（阶段 0）/ 昨天|前天 HH:mm（m1f）/ 当天 HH:mm
      var t = (el.innerText || '').trim();
      if (/^\d{2}-\d{2} \d{2}:\d{2}$/.test(t) || /^(昨天|前天) \d{1,2}:\d{2}$/.test(t) || /^\d{1,2}:\d{2}$/.test(t)) {
        events.push({ kind: 'time', text: t });
      }
    }
  }
  return JSON.stringify({ events: events, cards: [] });
})()
"""

PREPARE_JS = r"""
(function () {
  var TEXT = %(text)s;
  var ta = Array.prototype.slice.call(document.querySelectorAll('textarea'))
    .filter(function (e) { return (e.getAttribute('placeholder') || '').indexOf('请输入消息') >= 0; })[0];
  if (!ta) { return JSON.stringify({ error: 'no-input' }); }
  var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
  setter.call(ta, TEXT);
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  ta.dispatchEvent(new Event('change', { bubbles: true }));
  try { ta.focus(); } catch (e) {}
  return JSON.stringify({ value: ta.value, focused: document.activeElement === ta });
})()
"""

SEND_JS = r"""
(function () {
  return new Promise(function (resolve) {
    var textarea = function () {
      return Array.prototype.slice.call(document.querySelectorAll('textarea'))
        .filter(function (e) { return (e.getAttribute('placeholder') || '').indexOf('请输入消息') >= 0; })[0];
    };
    var before = textarea() ? textarea().value : null;
    var button = Array.prototype.slice.call(document.querySelectorAll('button.ant-btn'))
      .filter(function (b) {
        var rect = b.getBoundingClientRect();
        return (b.textContent || '').replace(/\s+/g, '') === '发送' && rect.width > 0 && rect.top > 300;
      })[0];
    if (!button) { resolve(JSON.stringify({ error: 'no-send-button', before: before })); return; }
    ['mouseover', 'mousedown', 'mouseup', 'click'].forEach(function (type) {
      button.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
    });
    try { button.click(); } catch (e) {}
    setTimeout(function () {
      var after = textarea() ? textarea().value : null;
      var messages = Array.prototype.slice.call(document.querySelectorAll('div[class*="message-text--"]'))
        .slice(-8)
        .map(function (node) {
          return { dir: /right/i.test(node.className) ? 'out' : 'in',
                   text: (node.innerText || '').replace(/\s+/g, ' ').slice(0, 80) };
        });
      resolve(JSON.stringify({ before: before, after: after, messages: messages,
                               sentFound: messages.some(function (m) { return m.dir === 'out'; }) }));
    }, 5000);
  });
})()
"""

# 发送区 toast（图片上传失败/格式超限等）——15 号文档图片外发失败判据
TOAST_JS = r"""
(function () {
  return JSON.stringify(Array.prototype.slice.call(
    document.querySelectorAll('.ant-message-notice-content, .ant-message-custom-content')
  ).map(function (el) {
    return (el.innerText || '').replace(/\s+/g, ' ').trim();
  }).filter(Boolean));
})()
"""

# 优先 sendbox 作用域 + accept 含 image；找不到再回退全文档（15A 评审 P1-2）
UPLOAD_INPUT_JS = r"""
(function () {
  function pick(root) {
    if (!root) return null;
    var list = Array.prototype.slice.call(root.querySelectorAll('input[type=file]'));
    var hit = list.filter(function (el) {
      return (el.getAttribute('accept') || '').toLowerCase().indexOf('image') >= 0;
    })[0];
    return hit || list[0] || null;
  }
  var box = document.querySelector('[class*="sendbox"]');
  return pick(box) || pick(document.body);
})()
"""

UPLOAD_INPUT_META_JS = r"""
(function () {
  function pick(root) {
    if (!root) return null;
    var list = Array.prototype.slice.call(root.querySelectorAll('input[type=file]'));
    var hit = list.filter(function (el) {
      return (el.getAttribute('accept') || '').toLowerCase().indexOf('image') >= 0;
    })[0];
    return hit || list[0] || null;
  }
  var box = document.querySelector('[class*="sendbox"]');
  var el = pick(box) || pick(document.body);
  if (!el) return JSON.stringify({ found: false });
  return JSON.stringify({ found: true, accept: el.getAttribute('accept'),
                          multiple: !!el.multiple,
                          inSendbox: !!el.closest('[class*="sendbox"]') });
})()
"""

# 单步滚动 + 可见行提取（10 号文档 M2.2：滚动循环 Python 化——COLLECT_JS 是单个长
# Promise（600ms×N 步），期间 on_progress/paused_check 无法触达、main_loop_stale_sec
# 会误判卡死；改每步一次 evaluate，步间回调）。行提取逻辑与 COLLECT_JS 保持一致。
STEP_COLLECT_JS = r"""
(function () {
  var holder = document.querySelector('.rc-virtual-list-holder');
  if (!holder || !holder.isConnected) return JSON.stringify({ error: 'no-list-holder' });
  var rows = [];
  Array.prototype.slice.call(document.querySelectorAll('div[class*="conversation-item--"]'))
    .forEach(function (row) {
      var raw = (row.innerText || '').replace(/\s+/g, ' ').trim();
      if (!raw) return;
      // P0-1（12 号文档）：骨架屏竞态守卫 + 结构法昵称提取（同 COLLECT_JS，未渲染行本步
      // 跳过——Python 步循环不记该 raw，下一步渲染完成后正常采集）
      var nickEl = null;
      var rowDivs = row.querySelectorAll('div');
      for (var k = 0; k < rowDivs.length; k++) {
        var st = rowDivs[k].getAttribute('style') || '';
        if (st.indexOf('font-size: 14px') >= 0 && st.indexOf('font-weight: 500') >= 0) { nickEl = rowDivs[k]; break; }
      }
      var nickText = '';
      if (nickEl) {
        // 同 COLLECT_JS：取第一个子元素避开订单状态标签后缀，仍空 = 骨架屏未渲染
        var c0 = nickEl.children && nickEl.children.length ? nickEl.children[0] : nickEl;
        nickText = (c0.innerText || '').replace(/\s+/g, ' ').trim();
        if (!nickText) nickText = (nickEl.innerText || '').replace(/\s+/g, ' ').trim();
        if (!nickText) return;
      }
      // 未读角标（同 COLLECT_JS）：ant-badge sup + 类名启发式兜底
      var unreadText = '';
      var badgeEl = row.querySelector('.ant-badge sup, .ant-scroll-number');
      if (badgeEl) unreadText = (badgeEl.innerText || '').trim();
      if (!unreadText) {
        Array.prototype.slice.call(row.querySelectorAll('[class*="unread"], [class*="badge"], [class*="dot"]'))
          .some(function (el) {
            var t = (el.innerText || '').trim();
            if (t) { unreadText = t; return true; }
            return false;
          });
      }
      // 叶子块全递归（同 COLLECT_JS）+ 过滤头像徽标子树
      var leaves = [];
      (function walk(el) {
        Array.prototype.slice.call(el.children).forEach(function (c) {
          if (c.closest && c.closest('.ant-badge')) return;
          if (!c.children.length) {
            var t = (c.innerText || '').replace(/\s+/g, ' ').trim();
            if (t && t.length <= 40) leaves.push(t);
          } else { walk(c); }
        });
      })(row);
      var parts = [];
      leaves.forEach(function (t) { if (parts[parts.length - 1] !== t) parts.push(t); });
      var timeText = '';
      for (var j = parts.length - 1; j >= 0; j--) {
        if (/前|昨天|\d{1,2}:\d{2}/.test(parts[j])) { timeText = parts[j]; break; }
      }
      // 会话行商品缩略图（同 COLLECT_JS）：排除头像（.ant-badge 子树）与订单状态小图标（<24px）
      var productImg = '';
      (function () {
        var cands = [];
        Array.prototype.slice.call(row.querySelectorAll('img')).forEach(function (im) {
          if (im.closest && im.closest('.ant-badge')) return;
          var rr = im.getBoundingClientRect();
          if (rr.width < 24) return;
          var s = im.getAttribute('data-actualsrc') || im.getAttribute('data-src')
                || im.currentSrc || im.src || im.getAttribute('src') || '';
          if (s) cands.push(s);
        });
        if (cands.length) productImg = cands[cands.length - 1];
      })();
      // 会话唯一身份（2026-09-24 会话唯一性修复）：会话行元素挂在 React fiber 上，
      // 沿 return 链取 memoizedProps.sessionInfo 可直接读到（**无需点开会话**）：
      //   sessionId   = 会话唯一 id（与页面 activeCid 同体系，即落库 chat_id）
      //   itemInfo    = 会话所属商品 {itemId, title, mainPic}——修复「未关联商品」的数据源
      //   userInfo.userId = 买家 id（buyer_id，可绕开昵称脱敏）
      // 整段 try/catch：站点改版/结构变化时静默降级为纯 DOM 采集（即旧行为，不影响可用性）。
      var sessionId = '', convItemId = '', convItemTitle = '', convItemPic = '', convBuyerId = '';
      (function () {
        try {
          var ks = Object.keys(row), f = null;
          for (var fi = 0; fi < ks.length; fi++) {
            if (ks[fi].indexOf('__reactFiber$') === 0) { f = row[ks[fi]]; break; }
          }
          var depth = 0, sess = null;
          while (f && depth < 8) {
            var pr = f.memoizedProps;
            if (pr && pr.sessionInfo && typeof pr.sessionInfo === 'object') { sess = pr.sessionInfo; break; }
            f = f.return; depth++;
          }
          if (sess) {
            sessionId = sess.sessionId ? String(sess.sessionId) : '';
            var it = sess.itemInfo || {};
            convItemId = it.itemId ? String(it.itemId) : '';
            convItemTitle = it.title ? String(it.title).slice(0, 255) : '';
            convItemPic = it.mainPic ? String(it.mainPic) : '';
            convBuyerId = (sess.userInfo && sess.userInfo.userId) ? String(sess.userInfo.userId) : '';
          }
        } catch (e) {}
      })();
      rows.push({
        raw: raw,
        parts: parts.slice(0, 6),
        nick: nickText || parts[0] || '',
        time_text: timeText,
        unread_text: unreadText,
        unread_count: /^\d+$/.test(unreadText) ? parseInt(unreadText, 10) : null,
        product_img: productImg,
        session_id: sessionId,
        item_id: convItemId,
        item_title: convItemTitle,
        item_pic: convItemPic,
        buyer_id: convBuyerId
      });
    });
  // 滚动一步（方向由 Python 传参；atTop/atBottom 供 Python 侧切换扫描相）
  var before = holder.scrollTop;
  var phase = %(phase)s;
  var atTop = false, atBottom = false;
  if (phase === 'up') {
    holder.scrollTop -= holder.clientHeight * 0.8;
    atTop = holder.scrollTop >= before - 1;
  } else {
    holder.scrollTop += holder.clientHeight * 0.8;
    atBottom = holder.scrollTop <= before + 1;
  }
  return JSON.stringify({ rows: rows, scrollTop: before, atTop: atTop, atBottom: atBottom });
})()
"""

# 会话列表渲染探针（2026-09-30 vm101 漏读根因）：把「rows=[]」拆成四种可区分的态——
#   ① 行容器 0（列表真空/页面未加载）        → 等下一轮即可
#   ② 行容器 >0 但昵称渲染 0（骨架屏停滞）   → **页面自身不会退出该状态**（实测持续 7.5h+），
#      必须软重载/重建页面，仅凭行列数无法与 ① 区分
#   ③ needLogin（Cookie 失效）               → 走 login_required/BLOCKED
#   ④ blocked（非法访问风控）                → 走 punish 冷却
# 采集侧（STEP_COLLECT_JS）在行级 `nickText` 为空时 `return` 丢弃该行，故 ② 表现为 rows=0，
# 诊断与自愈都需要「为何为空」的计数，故单独提供本探针（只读，无副作用、不滚动列表）。
LIST_PROBE_JS = r"""
(function () {
  var holder = document.querySelector('.rc-virtual-list-holder');
  var rows = document.querySelectorAll('div[class*="conversation-item--"]');
  var withNick = 0;
  Array.prototype.slice.call(rows).forEach(function (row) {
    var divs = row.querySelectorAll('div');
    for (var k = 0; k < divs.length; k++) {
      var st = divs[k].getAttribute('style') || '';
      if (st.indexOf('font-size: 14px') >= 0 && st.indexOf('font-weight: 500') >= 0) {
        if ((divs[k].innerText || '').replace(/\s+/g, ' ').trim()) withNick++;
        break;
      }
    }
  });
  var text = (document.body && document.body.innerText) || '';
  return JSON.stringify({
    url: location.href,
    holder: !!holder,
    convItems: rows.length,
    nickRendered: withNick,
    skeleton: document.querySelectorAll('.ant-skeleton').length,
    blocked: text.indexOf('非法访问') >= 0,
    needLogin: /\/login|请登录|扫码登录/.test(text)
  });
})()
"""

# 会话页身份闸门（P1-4）：判断聊天区 header 是否出现买家昵称 + 提取买家 userId。
# ✅ 已按 vm101 真实 DOM 校准（2026-09-22 M2 探针）：
#   - 闲鱼 webim 为双栏布局：左侧 329px 会话列表栏常驻（打开会话后不消失），右侧聊天区；
#   - 买家昵称叶子在聊天区 header（top≈90~122、left≥340，位于「闲鱼号」按钮上方）；
#   - 原版只扫 top≤120 会漏掉昵称 → 上限放宽到 220；
#   - 必须限定 left≥340：左侧列表行同样含买家昵称文本，不限定会把列表行当
#     header 命中，身份闸门形同虚设（点 A 行验证 B 行昵称也通过）；
#   - buyerId 来源：聊天区 header「闲鱼号」链接 href="/personal?userId=<数字>"
#     （探针实测对方主页链接带 userId，我方主页链接无参数，天然无歧义；
#     WS 帧 sender.uid 无法区分我方/对方，不用）。
HEADER_JS = r"""
(function () {
  var NICK = %(nick)s;
  var texts = [];
  Array.prototype.slice.call(document.querySelectorAll('div, span'))
    .forEach(function (el) {
      if (el.children.length) return;
      var r = el.getBoundingClientRect();
      if (r.top < 0 || r.top > 220 || r.left < 340 || r.width <= 0 || r.height <= 0) return;
      var t = (el.innerText || '').replace(/\s+/g, ' ').trim();
      if (t && t.length <= 60 && texts.indexOf(t) < 0) texts.push(t);
    });
  var joined = texts.join(' | ');
  var buyerId = '';
  var a = document.querySelector('a[href*="/personal?userId="]');
  if (a) {
    var m = (a.getAttribute('href') || '').match(/userId=(\d+)/);
    if (m) buyerId = m[1];
  }
  // 会话唯一 id 校验（2026-09-24）：会话行 props.activeCid = 当前激活会话的 sessionId；
  // 同一买家多会话昵称相同，仅校验昵称无法区分点对了哪一行，故回传 activeCid 供 Python 侧比对
  var activeCid = '';
  (function () {
    try {
      var rows = Array.prototype.slice.call(document.querySelectorAll('div[class*="conversation-item--"]'));
      for (var i = 0; i < rows.length && !activeCid; i++) {
        var ks = Object.keys(rows[i]), f = null;
        for (var j = 0; j < ks.length; j++) {
          if (ks[j].indexOf('__reactFiber$') === 0) { f = rows[i][ks[j]]; break; }
        }
        var depth = 0;
        while (f && depth < 8) {
          var p = f.memoizedProps;
          if (p && p.activeCid) { activeCid = String(p.activeCid); break; }
          f = f.return; depth++;
        }
      }
    } catch (e) {}
  })();
  return JSON.stringify({ header: joined.slice(0, 200), hasNick: joined.indexOf(NICK) >= 0,
                          buyerId: buyerId, activeCid: activeCid });
})()
"""

# 返回会话列表（发送后导航复位 / 多会话读取间隔）。
# ✅ vm101 实测（2026-09-22 M2 探针）：闲鱼 webim 为双栏布局，左侧会话列表栏常驻，
#   打开会话后 .rc-virtual-list-holder 仍在 DOM → ready().hasList 恒 true，
#   _ensure_list 靠 hasList 短路返回，本脚本实际不会被调用。候选选择器在真实 DOM
#   全未命中（无返回按钮），保留仅作未来全屏视图的防御；未命中时由 Python 侧
#   navigate 兜底。
BACK_JS = r"""
(function () {
  var cands = [];
  ['.anticon-arrow-left', '[class*="back"][class*="icon"]', '[aria-label*="返回"]',
   '[class*="header"] [class*="back"]', '[class*="header"] .anticon-left',
   '[class*="back"] svg'].forEach(function (sel) {
    try {
      var el = document.querySelector(sel);
      if (el && cands.indexOf(el) < 0) cands.push(el);
    } catch (e) {}
  });
  var hit = null;
  cands.some(function (el) {
    var r = el.getBoundingClientRect();
    if (r.top < 90 && r.left < 120 && r.width > 0 && r.height > 0) { hit = el; return true; }
    return false;
  });
  if (!hit) return JSON.stringify({ error: 'no-back-button' });
  ['mouseover', 'mousedown', 'mouseup', 'click'].forEach(function (type) {
    hit.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
  });
  try { hit.click(); } catch (e) {}
  return JSON.stringify({ clicked: true, tag: hit.tagName, cls: String(hit.className || '').slice(0, 80) });
})()
"""


# --------------------------------------------------------------------------- #
# 浏览器
# --------------------------------------------------------------------------- #
class Browser:
    """启动/连接一个带调试端口的 Edge/Chrome，并管理隔离上下文。

    ⚠️ 运行纪律（09 §2.7）：浏览器须长驻复用，禁止每轮任务启停（短时高频开关
    曾触发 3 连 x5sec punish）。
    """

    def __init__(self, port: int = 9333, headless: bool = True,
                 profile_dir: str | None = None, browser_path: str | None = None,
                 log=None):
        self.port = port
        self.headless = headless
        self.profile_dir = profile_dir or os.path.join(os.path.expanduser("~"), ".xianyu-webim", "profile")
        self.browser_path = browser_path or find_browser()
        self.client: CDP | None = None
        self.log = log

    # -- 生命周期 ---------------------------------------------------------- #
    def start(self, timeout: float = 90.0) -> "Browser":
        if not self.browser_path:
            raise RuntimeError("未找到 Edge/Chrome，请用 browser_path 指定可执行文件")
        # 阶段 0 踩坑：残留进程锁死 profile 致调试口未就绪 → 启动前清理
        killed = _kill_profile_processes(self.profile_dir)
        if killed and self.log:
            self.log.warning("清理残留浏览器进程 %d 个（profile: %s）", killed, self.profile_dir)
        os.makedirs(self.profile_dir, exist_ok=True)
        args = [self.browser_path]
        if self.headless:
            args.append("--headless=new")
        args += [
            f"--remote-debugging-port={self.port}",
            f"--user-data-dir={self.profile_dir}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-gpu",
            # 2026-09-22 vm101 发送失败根因：Agent 页面若为后台标签页，Edge 会把
            # setTimeout 节流到分钟级，OPEN_JS/COLLECT_JS 的 600ms 滚动链从 24s
            # 膨胀到几十分钟 → CDP 90s 超时 → 打开会话失败死循环。禁用后台 timer
            # 节流 + 窗口遮挡判定，保证页面即使非激活也按前台节奏执行 JS。
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            "--disable-features=CalculateNativeWinOcclusion",
            # 不恢复上次标签页：profile 恢复的旧页面（如 alimebot 客服页）会抢前台，
            # 把 Agent 页面挤成后台标签页触发 timer 节流（2026-09-22 实测）；
            # Cookie 存于 profile 存储，不受此参数影响。
            "--no-restore-session-state",
            "about:blank",
        ]
        kwargs = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
        if platform.system() == "Windows":
            kwargs["creationflags"] = 0x00000008 | 0x00000200   # DETACHED_PROCESS | NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
        subprocess.Popen(args, **kwargs)
        if not wait_port(self.port, timeout=timeout):
            raise RuntimeError(f"浏览器调试端口未就绪: {self.port}")
        return self.connect()

    def connect(self) -> "Browser":
        """连接已经在运行的浏览器（适合浏览器由计划任务/用户会话启动的场景）。"""
        version = http_json(f"http://127.0.0.1:{self.port}/json/version")
        self.client = CDP(version["webSocketDebuggerUrl"])
        return self

    def close(self) -> None:
        if self.client:
            try:
                self.client.call("Browser.close")
            except Exception:
                pass
            self.client.close()
            self.client = None
        # 阶段 0 踩坑：退出后清理残留进程树，防下次启动锁 profile
        _kill_profile_processes(self.profile_dir)

    # -- 上下文 / 页面 ------------------------------------------------------ #
    def new_context(self) -> str:
        result = self.client.call("Target.createBrowserContext")
        return result["result"]["browserContextId"]

    def dispose_context(self, context_id: str) -> None:
        try:
            self.client.call("Target.disposeBrowserContext", {"browserContextId": context_id})
        except Exception:
            pass

    def new_page(self, context_id: str, url: str = "about:blank") -> tuple[str, str]:
        created = self.client.call("Target.createTarget", {"url": url, "browserContextId": context_id})
        target_id = created["result"]["targetId"]
        attached = self.client.call("Target.attachToTarget", {"targetId": target_id, "flatten": True})
        return attached["result"]["sessionId"], target_id

    def close_page(self, target_id: str) -> None:
        try:
            self.client.call("Target.closeTarget", {"targetId": target_id})
        except Exception:
            pass


def convert_cookies(cookies: list[dict]) -> list[dict]:
    """把闲管家存储里的 Cookie 结构转换为 CDP Network.setCookies 需要的结构。"""
    payload = []
    for cookie in cookies or []:
        item = {
            "name": cookie.get("name"),
            "value": cookie.get("value", ""),
            "domain": cookie.get("domain"),
            "path": cookie.get("path", "/"),
            "secure": bool(cookie.get("secure")),
            "httpOnly": bool(cookie.get("httpOnly")),
        }
        if cookie.get("expirationDate"):
            item["expires"] = cookie["expirationDate"]
        if cookie.get("sameSite") in ("Strict", "Lax", "None"):
            item["sameSite"] = cookie["sameSite"]
        if item["name"] and item["domain"]:
            payload.append(item)
    return payload


# --------------------------------------------------------------------------- #
# 单个账号的页面
# --------------------------------------------------------------------------- #
class AccountPage:
    """一个闲鱼号对应一个隔离上下文里的一个页面。"""

    def __init__(self, browser: Browser, account: dict, log=None):
        self.browser = browser
        self.account = account
        self.log = log
        self.context_id: str | None = None
        self.session_id: str | None = None
        self.target_id: str | None = None

    # -- 打开 / 关闭 -------------------------------------------------------- #
    def ensure_active(self) -> bool:
        """激活本页面标签页（2026-09-22 vm101 发送失败根因修复）。

        页面若为后台标签页，Edge 会节流 setTimeout（600ms→分钟级），OPEN_JS/
        COLLECT_JS 滚动链从 24s 膨胀到几十分钟 → CDP 90s 超时。激活幂等且开销
        极小，在每次滚动链 evaluate 前调用；失败不致命（headless 无窗口概念）。
        """
        if not self.target_id or not self.browser.client:
            return False
        try:
            self.browser.client.call("Target.activateTarget", {"targetId": self.target_id}, timeout=5)
            return True
        except Exception:
            return False

    def open(self, ready_timeout: float = 60.0, retries: int = 3, poll: float = 2.0,
             progress=None) -> bool:
        """建 context → 注入 Cookie/UA → navigate → 等列表渲染。

        P2-4：页面就绪等待不沿用参考实现 20s——vm101 2 vCPU 软渲染实测 40~60s，
        默认 60s × 3 次重试；blocked（UA 伪装未生效）立即失败不重试。
        progress：每个 poll 周期回调一次（rehydration 长阻塞期间维持主循环心跳，
        guard.main_loop_stale_sec=90 无条件生效，不回调会被误判卡死杀进程）。

        ⚠️ 重试不再无条件 navigate（2026-09-22 vm101 根因修复）：冷启动页面渲染
        实测 >60s，超时即重载会把即将渲染完成的页面打回加载态 → 60s×3 重载死循环
        → 快照剔除进程退出。第 2 次起仅在页面 URL 已偏离（非 goofish/登录页）或
        探测失败时才 navigate；其余情况延长等待（页面已在渲染途中）。
        """
        client = self.browser.client
        self.context_id = self.browser.new_context()
        self.session_id, self.target_id = self.browser.new_page(self.context_id, "about:blank")

        client.call_session(self.session_id, "Network.enable")
        client.call_session(self.session_id, "Network.setUserAgentOverride", {
            "userAgent": APP_UA, "acceptLanguage": "zh-CN,zh;q=0.9", "platform": "Win32",
        })
        client.call_session(self.session_id, "Network.setExtraHTTPHeaders", {
            "headers": {"Referer": "https://www.goofish.com/", "Origin": "https://www.goofish.com"},
        })
        client.call_session(self.session_id, "Network.setCookies",
                            {"cookies": convert_cookies(self.account.get("cookies"))})
        client.call_session(self.session_id, "Page.enable")

        for attempt in range(1, retries + 1):
            navigate = attempt == 1
            if not navigate:
                # 页面可能仍在渲染（重载会打断）：仅 URL 明显偏离才重载
                try:
                    st = self.ready()
                    u = (st or {}).get("url") or ""
                    if "goofish" not in u or (st and st.get("needLogin")):
                        navigate = True
                except Exception:
                    navigate = True
            if navigate:
                client.call_session(self.session_id, "Page.navigate", {"url": self.account["webview_src"]})
            # 激活本页标签页：防被其他标签页（如闲小蜜 alimebot 页）抢前台后节流
            self.ensure_active()
            deadline = time.time() + ready_timeout
            while time.time() < deadline:
                time.sleep(poll)
                if progress:
                    try:
                        progress()
                    except Exception:
                        pass
                state = self.ready()
                if state and state.get("blocked"):
                    raise RuntimeError("页面提示非法访问：UA 伪装未生效")
                if state and state.get("reconnected") and self.log:
                    self.log.warning("检测到「连接中断，请重连」弹窗，已自动点击重连，继续等待列表渲染")
                if state and state.get("hasList"):
                    return True
            if self.log:
                self.log.warning("页面就绪等待超时（第 %d/%d 次，%ss），重试", attempt, retries, ready_timeout)
        return False

    def ready(self) -> dict | None:
        raw = self.browser.client.evaluate(READY_JS, session_id=self.session_id)
        return json.loads(raw) if isinstance(raw, str) else raw

    def probe_list(self) -> dict:
        """会话列表渲染探针（见 LIST_PROBE_JS）：区分「列表真空 / 骨架屏停滞 / 登录失效 / 风控」。

        只读、无副作用（不滚动列表）；异常由调用方兜底为「未渲染」（通道侧 try 包裹）。
        """
        raw = self.browser.client.evaluate(LIST_PROBE_JS, session_id=self.session_id)
        return json.loads(raw) if isinstance(raw, str) else (raw or {})

    def close(self) -> None:
        if self.target_id:
            self.browser.close_page(self.target_id)
        if self.context_id:
            self.browser.dispose_context(self.context_id)
        self.session_id = self.target_id = self.context_id = None

    # -- 读 ---------------------------------------------------------------- #
    def collect_conversations(self, scroll_steps: int = 30, retries: int = 3) -> list[dict]:
        """滚动虚拟列表，返回结构化会话行（昵称/未读角标/相对时间/预览 + raw 兜底）。

        vm101 实测：页面初始化期执行会拿到空结果（holder 重建窗口），空结果时等 3s 重试。
        """
        self.ensure_active()  # 防后台标签页 timer 节流（2026-09-22 根因）
        for attempt in range(retries):
            js = COLLECT_JS % {"steps": scroll_steps}
            raw = self.browser.client.evaluate(js, session_id=self.session_id)
            if isinstance(raw, str):
                try:
                    data = json.loads(raw)
                except json.JSONDecodeError:
                    data = {}
                rows = data.get("rows") or []
                if rows:
                    return rows
            time.sleep(3)
        return []

    def open_conversation(self, nick: str, max_steps: int = 40, settle_seconds: float = 10.0,
                          skip: int = 0, session_id: str | None = None) -> str:
        """边滚动边找，找到目标会话并点开；点开后等待消息渲染出来。

        定位优先级（2026-09-24 会话唯一性）：session_id 非空 → 按会话行 fiber 的
        sessionInfo.sessionId 精确定位（同一买家多商品会话昵称相同，仅按昵称只能命中第一行）；
        session_id 为空 → 退回昵称匹配（旧行为）。
        skip>0：昵称回退路径下点第 skip+1 个命中行（P1-4 多行同名买家逐行验证 header 用）。
        settle_seconds=0：点开即返回——WS 帧收集窗口须紧贴点击（CDP.call 响应循环
        会吞掉事件消息，错过即丢），由 WebimChannel._open_verified 自行 pump。
        """
        self.ensure_active()  # 防后台标签页 timer 节流（2026-09-22 根因）
        js = OPEN_JS % {"nick": json.dumps(nick, ensure_ascii=False),
                        "sid": json.dumps(session_id or "", ensure_ascii=False),
                        "max_steps": max_steps, "skip": skip}
        raw = self.browser.client.evaluate(js, session_id=self.session_id)
        result = raw if isinstance(raw, str) else str(raw)
        if result.startswith("clicked"):
            self.wait_for_messages(settle_seconds)
        return result

    def wait_for_messages(self, timeout: float = 10.0, poll: float = 1.0) -> bool:
        """等待消息区渲染出至少一条消息（点开会话后需要一点时间）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.read_messages()["events"]:
                return True
            time.sleep(poll)
        return False

    def read_messages(self) -> dict:
        """读消息区：{events: [{kind: time|msg, ...}], cards: [...]}。

        events 为 DOM 顺序事件流，Python 侧用 attach_times() 做时间锚点向下传播。
        """
        raw = self.browser.client.evaluate(MESSAGES_JS, session_id=self.session_id)
        data = json.loads(raw) if isinstance(raw, str) else (raw or {})
        return data

    # -- 写 ---------------------------------------------------------------- #
    def prepare_text(self, text: str) -> dict:
        """写入输入框但不发送（用于人工确认或调试）。"""
        js = PREPARE_JS % {"text": json.dumps(text, ensure_ascii=False)}
        raw = self.browser.client.evaluate(js, session_id=self.session_id)
        return json.loads(raw) if isinstance(raw, str) else raw

    def send_text(self, text: str) -> dict:
        """写输入框 → 点发送 → 读 DOM 校验；返回发送前后的输入框值与最后几条消息。"""
        self.prepare_text(text)
        raw = self.browser.client.evaluate(SEND_JS, session_id=self.session_id)
        return json.loads(raw) if isinstance(raw, str) else raw

    def upload_image(self, path: str) -> dict:
        """把本地图片注入 sendbox 隐藏 file input（15 号文档主路径 A）。

        注入后由客户端 antd Upload 自行上传并 sendImage，Agent 不点发送。
        """
        client = self.browser.client
        object_id = None
        try:
            client.call_session(self.session_id, "DOM.enable")
            raw = client.evaluate(UPLOAD_INPUT_META_JS, session_id=self.session_id)
            meta = json.loads(raw) if isinstance(raw, str) else (raw or {})
            if not meta.get("found"):
                return {"ok": False, "error": "no-file-input"}
            if self.log:
                self.log.debug("upload_image input: %s", meta)
            object_id = client.evaluate_handle(UPLOAD_INPUT_JS, session_id=self.session_id)
            if not object_id:
                return {"ok": False, "error": "no-file-input-handle"}
            res = client.call_session(
                self.session_id, "DOM.setFileInputFiles",
                {"files": [str(path)], "objectId": object_id})
            if res.get("error"):
                return {"ok": False, "error": str(res.get("error"))[:300]}
            return {"ok": True, "objectId": object_id}
        except Exception as e:
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}
        finally:
            # 释放 RemoteObject 引用，避免每次发图泄漏（15A 评审 P3）
            if object_id:
                try:
                    client.call_session(self.session_id, "Runtime.releaseObject",
                                        {"objectId": object_id})
                except Exception:
                    pass

    def out_media_events(self) -> list[dict]:
        """当前 DOM 里我方的媒体事件（图片/表情/语音/视频）。"""
        data = self.read_messages()
        return [e for e in (data.get("events") or [])
                if e.get("kind") == "media" and e.get("dir") == "out"]

    def toasts(self) -> list[str]:
        """当前 antd message 文本（客户端上传失败/格式超限提示）。"""
        raw = self.browser.client.evaluate(TOAST_JS, session_id=self.session_id)
        return json.loads(raw) if isinstance(raw, str) else (raw or [])


def load_accounts(path: str) -> list[dict]:
    """读 accounts.json（调试口导出的账号 + Cookie 缓存，Cookie 主源）。"""
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


# --------------------------------------------------------------------------- #
# WS 帧收集（buyer_id / chat_id，09 §2.3）
# --------------------------------------------------------------------------- #
class WsFrames:
    """在「无并发调用」窗口内主动 pump CDP 事件，从 WS 帧解析 buyer_id / chat_id。

    阶段 0 实测（09 §2.3）：单聊帧含 `"cid":"66369663325@goofish"`（=chat_id，与
    埋点 sessionId 同源）、`"pairSecond":"<数字>@goofish"`（=对方 userId）、
    `ownerUserId`（我方）。⚠️ CDP.call 的响应循环会吞掉事件消息，帧收集只能由本类
    主动 recv（阶段 0 Recorder 同法），期间不可并发调用 CDP；结束后恢复阻塞超时。
    """

    def __init__(self, cdp: CDP):
        self.cdp = cdp
        self.frames: list[str] = []
        self.last_ids: dict = {"chat_id": None, "buyer_id": None}

    def clear(self) -> None:
        """打开新会话前清空（防上一会话帧串号）；last_ids 一并重置。"""
        self.frames.clear()
        self.last_ids = {"chat_id": None, "buyer_id": None}

    def pump(self, seconds: float) -> None:
        """收集 seconds 内的 webSocketFrameReceived 帧；越往后帧越新（后到覆盖）。"""
        ws = self.cdp.ws
        prev = ws.sock.gettimeout()
        try:
            ws.sock.settimeout(0.5)
            end = time.time() + seconds
            while time.time() < end:
                try:
                    data = json.loads(ws.recv_text())
                except (socket.timeout, OSError, json.JSONDecodeError):
                    continue
                except Exception:
                    break
                if data.get("method") != "Network.webSocketFrameReceived":
                    continue
                payload = ((data.get("params") or {}).get("response") or {}).get("payloadData") or ""
                if not payload or len(payload) > 3000:
                    continue
                self.frames.append(payload)
                self._extract_ids(payload)
        finally:
            try:
                ws.sock.settimeout(prev)
            except OSError:
                pass

    def _extract_ids(self, payload: str) -> None:
        m = re.search(r'"cid":"(\d+)@goofish"', payload)
        if m:
            self.last_ids["chat_id"] = m.group(1)
        m = re.search(r'"pairSecond":"(\d+)@goofish"', payload)
        if m and m.group(1) != "0":
            self.last_ids["buyer_id"] = m.group(1)
