"""OCR 封装 + 文字定位（POC ocr_ui.py 演进，03 号文档 §5）。

RapidOCR（rapidocr-onnxruntime）。识别输入支持图片路径与内存 BGR ndarray（生产走内存，
见 win.grab_window / win.grab_area）。缓存默认关闭（M3 评审 P2-1：扫描循环复用文件名时，
若命中旧 .ocr.json 会读到过期文本；缓存仅 POC 调试显式 use_cache=True 开启）。
低置信度重拍策略在调用方（03 §0.1-8），本模块只提供基础识别能力。
"""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path

import numpy as np
from rapidocr_onnxruntime import RapidOCR


def _default_threads() -> int:
    """onnxruntime intra-op 线程数默认值。

    不取包默认的 -1（= 逻辑核数）：实测 1280×722 全窗图在 20 逻辑核开发机上
    -1 → 中位 8.3s，限 4 → 1.08s（线程过多在大量小识别框上纯亏调度）。
    2 vCPU 试点机上取值 2 与默认一致，故该项对 VM 中性、对高核宿主机是数量级收益。
    """
    return max(1, min(4, os.cpu_count() or 2))


class Ocr:
    """RapidOCR 封装：截图（路径或内存 ndarray）→ 文字条目列表（含坐标与置信度）。"""

    def __init__(self, lang: str = "ch", min_confidence: float = 0.85,
                 use_cls: bool = False, intra_op_num_threads: int = 0,
                 log: logging.Logger | None = None):
        self.min_confidence = min_confidence
        log = log or logging.getLogger("xianyu-agent")
        threads = int(intra_op_num_threads) or _default_threads()
        # RapidOCR 只认 flat kwargs：旧的 RapidOCR(params={"Global.lang": lang}) 形式里 params
        # 被 UpdateParameters 当成一个叫 params 的 Global 键丢弃（Global 段本无 lang），
        # 等于 lang/use_cls/线程数全部从未生效——实测传 use_cls=False 后实例属性仍为 True。
        kwargs = {"use_cls": bool(use_cls),
                  "intra_op_num_threads": threads,
                  "inter_op_num_threads": 1}
        try:
            self.engine = RapidOCR(**kwargs)
        except TypeError:  # 旧版本不接受这些 kwargs
            self.engine = RapidOCR()
            log.warning("RapidOCR 版本不支持调参 kwargs，OCR 性能参数（use_cls/线程数）未生效")
        if lang and lang != "ch":
            log.warning("ocr.lang=%s 未生效：rapidocr_onnxruntime 仅内置中文模型，换语言须传 config_path", lang)
        if getattr(self.engine, "use_cls", None) != bool(use_cls):
            log.warning("ocr.use_cls=%s 未被 RapidOCR 接受（实际 %s）", use_cls,
                        getattr(self.engine, "use_cls", None))

    def warmup(self) -> None:
        """预热模型（03 §2.1 INIT：OCR 初始化 + 预热 1 张空白图，不落盘）。"""
        self.run(np.zeros((32, 32, 3), dtype=np.uint8))

    def run(self, src: str | Path | np.ndarray, use_cache: bool = False) -> list[dict]:
        """识别图片，返回 [{text, score, x0, y0, x1, y1}]（坐标为截图内像素）。

        use_cache 默认 False（M3 评审 P2-1）：生产扫描路径必须关缓存，避免复用文件名时读到过期文本；
        仅 POC 调试可显式开启。传入内存 ndarray 时无文件可缓存，一律忽略 use_cache。
        """
        cache: Path | None = None
        if isinstance(src, np.ndarray):
            use_cache = False
        else:
            src = Path(src)
            cache = src.with_suffix(".ocr.json")
            if use_cache and cache.exists():
                return json.loads(cache.read_text(encoding="utf-8"))
        res, _ = self.engine(src)
        items = []
        if res:
            for box, text, score in res:
                xs = [p[0] for p in box]
                ys = [p[1] for p in box]
                items.append(
                    {
                        "text": str(text),
                        "score": round(float(score), 3),
                        "x0": int(min(xs)),
                        "y0": int(min(ys)),
                        "x1": int(max(xs)),
                        "y1": int(max(ys)),
                    }
                )
        if use_cache and cache is not None:
            cache.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
        return items

    def high_conf(self, items: list[dict]) -> list[dict]:
        """过滤出 ≥ min_confidence 的条目。"""
        return [it for it in items if it["score"] >= self.min_confidence]

    @staticmethod
    def find_text(items: list[dict], needle: str, exact: bool = False) -> list[dict]:
        matches = []
        for it in items:
            t = it["text"]
            if exact:
                ok = t == needle
            else:
                ok = needle in t
            if ok:
                matches.append(it)
        return matches

    @staticmethod
    def center(it: dict) -> tuple[int, int]:
        return ((it["x0"] + it["x1"]) // 2, (it["y0"] + it["y1"]) // 2)
