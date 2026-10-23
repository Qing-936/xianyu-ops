"""打开会话前后帧 diff——点击是否真的引起了视图变化（07 号文档 T1.3）。

PhysiClaw `core/vision/change.py` 的裁剪版：目标是捕获「静默拒绝」（点击无效/被
1-2 秒的 Toast 拒绝，屏幕看起来毫无变化）。桌面窗口截图（mss 内存帧，BGR ndarray）
与 PhysiClaw 的相机拍摄不同：无振动、无自动曝光漂移，因此**省略**亮度对齐与
相位对齐两段；输入帧已经按窗口区域裁剪（聊天区不含输入框，无光标闪烁源）。

管线：
    灰度 → 高斯模糊(5x5，杀传感器/编码噪声) → absdiff → 阈值
    → 形态学开(3x3，擦除散点噪声与细对齐残边) → 双触发：
      分布式：变化像素占比 > diff_ratio（键盘弹出/页面切换）
      局部：  最大连通域 ≥ max(local_blob_min_px, local_blob_frac * 总像素)
             （角标 2→3、单行 Toast、单个数字等小面积但连贯的变化）

fail-open：opencv 未安装（返回 None）或两帧形状不一致（返回 None）时，
调用方不计数、不熔断——diff 是「辅助证据」，主证据是通道自身的
打开成功判定（header 出现买家昵称）。
"""
from __future__ import annotations

import numpy as np

try:
    import cv2

    _HAS_CV2 = True
except ImportError:  # pragma: no cover - 环境缺包时 fail-open
    cv2 = None
    _HAS_CV2 = False

# 局部连通域触发的最小面积占比（相对总像素，适配不同分辨率截图）
LOCAL_BLOB_FRAC = 0.0002

_BLUR_KSIZE = 5
_OPEN_KERNEL = np.ones((3, 3), np.uint8)


def frames_changed(before, after,
                   noise_threshold: int = 25,
                   diff_ratio: float = 0.003,
                   local_blob_min_px: int = 60) -> bool | None:
    """前后两帧（BGR ndarray）是否发生可见变化。

    True/False = 判得；None = 无法判定（opencv 缺失、帧为空或形状不一致）
    ——调用方必须 fail-open：None 不计数、不熔断。
    """
    if not _HAS_CV2:
        return None
    if before is None or after is None or before.shape != after.shape:
        return None
    if before.size == 0:
        return None
    a = cv2.GaussianBlur(cv2.cvtColor(before, cv2.COLOR_BGR2GRAY),
                         (_BLUR_KSIZE, _BLUR_KSIZE), 0)
    b = cv2.GaussianBlur(cv2.cvtColor(after, cv2.COLOR_BGR2GRAY),
                         (_BLUR_KSIZE, _BLUR_KSIZE), 0)
    mask = cv2.morphologyEx(
        (cv2.absdiff(a, b) > noise_threshold).astype(np.uint8),
        cv2.MORPH_OPEN, _OPEN_KERNEL)
    total = mask.size
    if int(np.count_nonzero(mask)) > diff_ratio * total:
        return True
    n, _, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    if n <= 1:
        return False
    largest = int(stats[1:, cv2.CC_STAT_AREA].max())
    return largest >= max(local_blob_min_px, LOCAL_BLOB_FRAC * total)
