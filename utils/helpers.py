"""工具函数：角度计算、向量运算等。"""

import math
from collections import deque
from typing import Deque, Tuple

import numpy as np


def distance(p1: Tuple[float, float], p2: Tuple[float, float]) -> float:
    """计算两点之间的欧氏距离。"""
    return math.hypot(p2[0] - p1[0], p2[1] - p1[1])


def angle_between_vectors(v1: np.ndarray, v2: np.ndarray) -> float:
    """计算两个向量之间的夹角（度）。"""
    cos_theta = np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-8)
    cos_theta = np.clip(cos_theta, -1.0, 1.0)
    return math.degrees(math.acos(cos_theta))


def vector_angle_to_horizontal(v: np.ndarray) -> float:
    """计算向量相对于水平方向的角度（度），逆时针为正。"""
    return math.degrees(math.atan2(-v[1], v[0]))  # y轴向下，取反


def smooth_value(history: Deque[float], new_value: float, window: int = 5) -> float:
    """对数值进行滑动窗口平滑。history 应为 deque(maxlen=window)。"""
    history.append(new_value)
    return sum(history) / len(history)


def finger_bend_angle(
    mcp: Tuple[float, float],
    pip: Tuple[float, float],
    tip: Tuple[float, float],
) -> float:
    """计算手指弯曲角度。

    mcp: 掌指关节, pip: 近端指间关节, tip: 指尖
    返回值越小表示弯曲越大（0度=完全弯曲，180度=完全伸直）。
    """
    v1 = np.array([mcp[0] - pip[0], mcp[1] - pip[1]], dtype=np.float64)
    v2 = np.array([tip[0] - pip[0], tip[1] - pip[1]], dtype=np.float64)
    return angle_between_vectors(v1, v2)


def fit_circle(points: list) -> Tuple[float, float, float]:
    """对一组2D点进行最小二乘圆拟合。

    返回 (cx, cy, radius)，拟合失败返回 (0, 0, 0)。
    """
    if len(points) < 3:
        return (0, 0, 0)

    pts = np.array(points, dtype=np.float64)
    x = pts[:, 0]
    y = pts[:, 1]

    # 代数圆拟合: (x-cx)^2 + (y-cy)^2 = r^2
    # 展开: x^2 + y^2 - 2*cx*x - 2*cy*y + (cx^2+cy^2-r^2) = 0
    # 令 A = -2*cx, B = -2*cy, C = cx^2+cy^2-r^2
    A = np.column_stack([x, y, np.ones(len(x))])
    b = -(x ** 2 + y ** 2)

    try:
        result, _, _, _ = np.linalg.lstsq(A, b, rcond=None)
        cx = -result[0] / 2
        cy = -result[1] / 2
        r = math.sqrt(cx ** 2 + cy ** 2 - result[2])
        return (cx, cy, r)
    except np.linalg.LinAlgError:
        return (0, 0, 0)


def circle_fit_error(points: list, cx: float, cy: float, r: float) -> float:
    """计算点到拟合圆的平均相对误差。"""
    if r <= 0:
        return float("inf")
    pts = np.array(points, dtype=np.float64)
    dists = np.linalg.norm(pts - np.array([cx, cy]), axis=1)
    return float(np.mean(np.abs(dists - r) / r))


def direction_label(angle: float) -> str:
    """将角度转换为八方向标签。"""
    if -22.5 <= angle < 22.5:
        return "右"
    elif 22.5 <= angle < 67.5:
        return "右上"
    elif 67.5 <= angle < 112.5:
        return "上"
    elif 112.5 <= angle < 157.5:
        return "左上"
    elif angle >= 157.5 or angle < -157.5:
        return "左"
    elif -157.5 <= angle < -112.5:
        return "左下"
    elif -112.5 <= angle < -67.5:
        return "下"
    elif -67.5 <= angle < -22.5:
        return "右下"
    return "静止"
