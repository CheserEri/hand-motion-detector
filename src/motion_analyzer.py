"""运动状态分析模块：基于关键点轨迹识别动态手势动作。"""

import time
from collections import deque
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Deque, List, Optional, Tuple

import numpy as np

from src.hand_detector import FINGER_TIP_IDS, HandResult
from utils.helpers import (
    circle_fit_error,
    direction_label,
    distance,
    fit_circle,
    finger_bend_angle,
    smooth_value,
    vector_angle_to_horizontal,
)


class MotionType(Enum):
    """识别到的运动类型。"""
    IDLE = auto()          # 静止
    MOVING = auto()        # 一般移动
    WAVING = auto()        # 挥手
    GRABBING = auto()      # 抓取（握拳）
    RELEASING = auto()     # 释放（张开）
    CIRCLE = auto()        # 画圈
    SWIPE_UP = auto()      # 向上滑动
    SWIPE_DOWN = auto()    # 向下滑动
    SWIPE_LEFT = auto()    # 向左滑动
    SWIPE_RIGHT = auto()   # 向右滑动


@dataclass
class MotionEvent:
    """一次运动事件。"""
    motion_type: MotionType
    timestamp: float
    direction: str       # 运动方向标签
    speed: float         # 运动速度（像素/帧）
    description: str     # 人类可读描述


@dataclass
class MotionState:
    """当前运动状态快照。"""
    speed: float = 0.0
    direction: str = "静止"
    direction_angle: float = 0.0
    current_motion: MotionType = MotionType.IDLE
    events: List[MotionEvent] = field(default_factory=list)


class MotionAnalyzer:
    """运动状态分析引擎。"""

    def __init__(self, config: dict):
        self.cfg = config.get("motion", {})

        self._smoothing_window = self.cfg.get("smoothing_window", 5)
        self._min_speed_threshold = self.cfg.get("direction_threshold", 3.0)
        self._wave_min_changes = self.cfg.get("wave_min_changes", 3)
        self._wave_time_window = self.cfg.get("wave_time_window", 1.5)
        self._grab_bend_threshold = self.cfg.get("grab_bend_threshold", 60)
        self._click_speed_threshold = self.cfg.get("click_speed_threshold", 15.0)
        self._circle_min_points = self.cfg.get("circle_min_points", 15)
        self._circle_fit_threshold = self.cfg.get("circle_fit_threshold", 0.25)

        # 状态追踪
        self._prev_center: Optional[Tuple[float, float]] = None
        self._speed_history: Deque[float] = deque(maxlen=self._smoothing_window)
        self._direction_history: Deque[Tuple[float, float]] = deque(maxlen=100)
        self._wave_direction_changes: Deque[float] = deque(maxlen=50)
        self._palm_center_history: Deque[Tuple[float, float, float]] = deque(maxlen=200)
        self._grab_state: Optional[str] = None  # "open" 或 "closed"
        self._last_event_time: float = 0
        self._event_cooldown: float = 0.5  # 事件冷却时间（秒）

    def analyze(self, hand: HandResult) -> MotionState:
        """分析一只手的运动状态，返回当前状态和触发的事件。"""
        now = time.time()
        events: List[MotionEvent] = []

        # 计算手掌中心（手腕 + 中指 MCP 的中点）
        wrist = hand.landmarks[0]
        middle_mcp = hand.landmarks[9]
        palm_center = (
            (wrist[0] + middle_mcp[0]) / 2,
            (wrist[1] + middle_mcp[1]) / 2,
        )

        # 计算速度和方向
        speed = 0.0
        direction_angle = 0.0
        direction_str = "静止"

        if self._prev_center is not None:
            dx = palm_center[0] - self._prev_center[0]
            dy = palm_center[1] - self._prev_center[1]
            raw_speed = np.hypot(dx, dy)

            # 平滑速度
            speed = smooth_value(self._speed_history, raw_speed, self._smoothing_window)

            if speed > self._min_speed_threshold:
                movement = np.array([dx, dy], dtype=np.float64)
                direction_angle = vector_angle_to_horizontal(movement)
                direction_str = direction_label(direction_angle)

        self._prev_center = palm_center

        # 记录轨迹（带时间戳）
        self._palm_center_history.append((palm_center[0], palm_center[1], now))
        self._direction_history.append((direction_angle, speed))

        # --- 动作识别 ---

        # 1. 滑动检测（速度超过阈值且持续单向）
        if speed > self._click_speed_threshold and direction_str != "静止":
            swipe_event = self._detect_swipe(direction_angle, speed, now)
            if swipe_event and (now - self._last_event_time) > self._event_cooldown:
                events.append(swipe_event)
                self._last_event_time = now

        # 2. 挥手检测（方向快速交替变化）
        wave_event = self._detect_wave(direction_str, speed, now)
        if wave_event and (now - self._last_event_time) > self._event_cooldown:
            events.append(wave_event)
            self._last_event_time = now

        # 3. 抓取/释放检测（手指弯曲状态变化）
        grab_event = self._detect_grab(hand, now)
        if grab_event and (now - self._last_event_time) > self._event_cooldown:
            events.append(grab_event)
            self._last_event_time = now

        # 4. 画圈检测
        circle_event = self._detect_circle(now)
        if circle_event and (now - self._last_event_time) > self._event_cooldown:
            events.append(circle_event)
            self._last_event_time = now

        # 确定当前运动类型
        current_motion = MotionType.IDLE
        if speed > self._min_speed_threshold:
            current_motion = MotionType.MOVING
        if events:
            current_motion = events[-1].motion_type

        return MotionState(
            speed=speed,
            direction=direction_str,
            direction_angle=direction_angle,
            current_motion=current_motion,
            events=events,
        )

    def reset(self):
        """重置所有追踪状态。"""
        self._prev_center = None
        self._speed_history.clear()
        self._direction_history.clear()
        self._wave_direction_changes.clear()
        self._palm_center_history.clear()
        self._grab_state = None

    def _detect_swipe(self, angle: float, speed: float, now: float) -> Optional[MotionEvent]:
        """检测快速滑动手势。"""
        # 检查最近几帧是否保持同方向高速运动
        recent = list(self._direction_history)[-5:]
        if len(recent) < 5:
            return None

        consistent = all(s > self._min_speed_threshold for _, s in recent)
        if not consistent:
            return None

        # 计算平均方向
        avg_angle = np.mean([a for a, _ in recent])
        label = direction_label(avg_angle)

        swipe_map = {
            "上": MotionType.SWIPE_UP,
            "下": MotionType.SWIPE_DOWN,
            "左": MotionType.SWIPE_LEFT,
            "右": MotionType.SWIPE_RIGHT,
        }
        motion = swipe_map.get(label)
        if motion is None:
            return None

        return MotionEvent(
            motion_type=motion,
            timestamp=now,
            direction=label,
            speed=speed,
            description=f"向{label}滑动 (速度: {speed:.1f})",
        )

    def _detect_wave(self, direction: str, speed: float, now: float) -> Optional[MotionEvent]:
        """检测挥手动作（左右快速往复）。"""
        if direction not in ("左", "右"):
            return None
        if speed < self._min_speed_threshold:
            return None

        self._wave_direction_changes.append((now, direction))

        # 清理过期记录
        cutoff = now - self._wave_time_window
        while self._wave_direction_changes and self._wave_direction_changes[0][0] < cutoff:
            self._wave_direction_changes.popleft()

        # 检查方向变化次数
        changes = 0
        prev_dir = None
        for _, d in self._wave_direction_changes:
            if prev_dir is not None and d != prev_dir:
                changes += 1
            prev_dir = d

        if changes >= self._wave_min_changes:
            self._wave_direction_changes.clear()
            return MotionEvent(
                motion_type=MotionType.WAVING,
                timestamp=now,
                direction="左右",
                speed=speed,
                description=f"挥手 (方向变化 {changes} 次)",
            )
        return None

    def _detect_grab(self, hand: HandResult, now: float) -> Optional[MotionEvent]:
        """检测抓取（握拳）和释放（张开）动作。"""
        # 计算四根手指（不含拇指）的平均弯曲角度
        bend_angles = []
        for tip_id, pip_id, mcp_id in zip(
            FINGER_TIP_IDS[1:],  # 跳过拇指
            [6, 10, 14, 18],     # 食指到小指的 PIP
            [5, 9, 13, 17],      # 食指到小指的 MCP
        ):
            mcp = hand.landmarks[mcp_id]
            pip = hand.landmarks[pip_id]
            tip = hand.landmarks[tip_id]
            angle = finger_bend_angle(mcp, pip, tip)
            bend_angles.append(angle)

        avg_bend = np.mean(bend_angles)
        is_closed = avg_bend < self._grab_bend_threshold

        if self._grab_state is None:
            self._grab_state = "closed" if is_closed else "open"
            return None

        if is_closed and self._grab_state == "open":
            self._grab_state = "closed"
            return MotionEvent(
                motion_type=MotionType.GRABBING,
                timestamp=now,
                direction="",
                speed=0,
                description=f"抓取 (弯曲角度: {avg_bend:.0f}°)",
            )
        elif not is_closed and self._grab_state == "closed":
            self._grab_state = "open"
            return MotionEvent(
                motion_type=MotionType.RELEASING,
                timestamp=now,
                direction="",
                speed=0,
                description=f"释放 (弯曲角度: {avg_bend:.0f}°)",
            )
        return None

    def _detect_circle(self, now: float) -> Optional[MotionEvent]:
        """检测画圈动作（指尖轨迹拟合圆形）。"""
        if len(self._palm_center_history) < self._circle_min_points:
            return None

        # 取最近的轨迹点
        recent = list(self._palm_center_history)[-self._circle_min_points:]
        points = [(p[0], p[1]) for p in recent]

        # 时间跨度检查（至少 0.5 秒）
        time_span = recent[-1][2] - recent[0][2]
        if time_span < 0.5:
            return None

        # 圆拟合
        cx, cy, r = fit_circle(points)
        if r < 20:  # 半径太小忽略
            return None

        error = circle_fit_error(points, cx, cy, r)
        if error < self._circle_fit_threshold:
            self._palm_center_history.clear()
            return MotionEvent(
                motion_type=MotionType.CIRCLE,
                timestamp=now,
                direction="",
                speed=0,
                description=f"画圈 (半径: {r:.0f}px, 误差: {error:.3f})",
            )
        return None
