"""运动轨迹记录与可视化模块。"""

from collections import deque
from typing import Deque, List, Optional, Tuple

import cv2
import numpy as np


# 轨迹颜色渐变（从旧到新：蓝 → 绿 → 黄 → 红）
_TRAJECTORY_COLORS = [
    (255, 100, 50),   # 蓝色（旧）
    (100, 255, 50),   # 绿色
    (50, 255, 255),   # 黄色
    (50, 100, 255),   # 红色（新）
]


def _lerp_color(c1: Tuple[int, int, int], c2: Tuple[int, int, int], t: float) -> Tuple[int, int, int]:
    """线性插值两个颜色。"""
    return (
        int(c1[0] + (c2[0] - c1[0]) * t),
        int(c1[1] + (c2[1] - c1[1]) * t),
        int(c1[2] + (c2[2] - c1[2]) * t),
    )


def _get_gradient_color(ratio: float) -> Tuple[int, int, int]:
    """根据比例 (0~1) 获取渐变颜色。"""
    ratio = max(0.0, min(1.0, ratio))
    n = len(_TRAJECTORY_COLORS) - 1
    segment = int(ratio * n)
    segment = min(segment, n - 1)
    local_t = (ratio * n) - segment
    return _lerp_color(_TRAJECTORY_COLORS[segment], _TRAJECTORY_COLORS[segment + 1], local_t)


class TrajectoryRecorder:
    """记录指尖轨迹并在帧上绘制。"""

    def __init__(self, max_length: int = 120, line_width: int = 2):
        self.max_length = max_length
        self.line_width = line_width
        self.enabled = True

        # 每根指尖一个轨迹
        self._trajectories: List[Deque[Tuple[int, int]]] = [
            deque(maxlen=max_length) for _ in range(5)
        ]

    def update(self, landmarks: Optional[List[Tuple[int, int]]]):
        """用最新的关键点更新轨迹。

        landmarks: 21个关键点坐标列表，或 None（无检测结果时清空）。
        """
        if landmarks is None or not self.enabled:
            return

        tip_indices = [4, 8, 12, 16, 20]  # 五根手指的指尖
        for i, tip_id in enumerate(tip_indices):
            if tip_id < len(landmarks):
                self._trajectories[i].append(landmarks[tip_id])

    def draw(self, frame: np.ndarray) -> np.ndarray:
        """在帧上绘制轨迹线。返回叠加了轨迹的帧。"""
        if not self.enabled:
            return frame

        overlay = frame.copy()

        for finger_traj in self._trajectories:
            points = list(finger_traj)
            if len(points) < 2:
                continue

            for i in range(1, len(points)):
                ratio = i / len(points)
                color = _get_gradient_color(ratio)
                thickness = max(1, int(self.line_width * (0.5 + ratio * 0.5)))
                cv2.line(overlay, points[i - 1], points[i], color, thickness, cv2.LINE_AA)

        return overlay

    def clear(self):
        """清空所有轨迹。"""
        for traj in self._trajectories:
            traj.clear()
