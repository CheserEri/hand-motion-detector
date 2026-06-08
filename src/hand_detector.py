"""手部关键点检测模块：封装 MediaPipe Hands。"""

from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import cv2
import mediapipe as mp
import numpy as np


@dataclass
class HandResult:
    """单只手的检测结果。"""
    landmarks: List[Tuple[float, float]]  # 21个关键点的像素坐标
    handedness: str  # "Left" 或 "Right"
    confidence: float  # 检测置信度
    bounding_box: Tuple[int, int, int, int]  # (x, y, w, h)


# MediaPipe 手部关键点索引
FINGER_TIP_IDS = [4, 8, 12, 16, 20]  # 拇指、食指、中指、无名指、小指的指尖
FINGER_PIP_IDS = [3, 6, 10, 14, 18]  # 对应的近端指间关节
FINGER_MCP_IDS = [2, 5, 9, 13, 17]  # 对应的掌指关节
WRIST_ID = 0


class HandDetector:
    """封装 MediaPipe Hands 手部检测器。"""

    def __init__(
        self,
        max_hands: int = 1,
        detection_confidence: float = 0.7,
        tracking_confidence: float = 0.5,
    ):
        self.mp_hands = mp.solutions.hands
        self.hands = self.mp_hands.Hands(
            static_image_mode=False,
            max_num_hands=max_hands,
            min_detection_confidence=detection_confidence,
            min_tracking_confidence=tracking_confidence,
        )
        self.mp_drawing = mp.solutions.drawing_utils
        self.mp_drawing_styles = mp.solutions.drawing_styles

    def detect(self, frame: np.ndarray) -> List[HandResult]:
        """检测帧中的手部，返回检测结果列表。"""
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        rgb.flags.writeable = False
        results = self.hands.process(rgb)

        hand_results: List[HandResult] = []
        if not results.multi_hand_landmarks:
            return hand_results

        h, w, _ = frame.shape
        for hand_landmarks, handedness in zip(
            results.multi_hand_landmarks, results.multi_handedness
        ):
            landmarks = []
            xs, ys = [], []
            for lm in hand_landmarks.landmark:
                px, py = int(lm.x * w), int(lm.y * h)
                landmarks.append((px, py))
                xs.append(px)
                ys.append(py)

            x_min, x_max = min(xs), max(xs)
            y_min, y_max = min(ys), max(ys)
            bbox = (x_min, y_min, x_max - x_min, y_max - y_min)

            label = handedness.classification[0].label
            score = handedness.classification[0].score

            hand_results.append(
                HandResult(
                    landmarks=landmarks,
                    handedness=label,
                    confidence=score,
                    bounding_box=bbox,
                )
            )

        return hand_results

    def draw_landmarks(self, frame: np.ndarray, hand: HandResult) -> np.ndarray:
        """在帧上原地绘制手部关键点和连线（不额外 copy）。"""
        # 绘制关键点连线
        connections = self.mp_hands.HAND_CONNECTIONS
        for start_idx, end_idx in connections:
            p1 = hand.landmarks[start_idx]
            p2 = hand.landmarks[end_idx]
            cv2.line(frame, p1, p2, (0, 255, 0), 2)

        # 绘制关键点
        tip_set = set(FINGER_TIP_IDS)
        for i, (x, y) in enumerate(hand.landmarks):
            if i in tip_set:
                cv2.circle(frame, (x, y), 5, (0, 0, 255), -1)
            else:
                cv2.circle(frame, (x, y), 3, (255, 255, 0), -1)

        # 绘制边界框
        x, y, w, h = hand.bounding_box
        cv2.rectangle(frame, (x - 10, y - 10), (x + w + 10, y + h + 10), (0, 255, 255), 2)

        # 绘制标签
        label = f"{hand.handedness} ({hand.confidence:.2f})"
        cv2.putText(frame, label, (x - 10, y - 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)

        return frame

    def release(self):
        """释放 MediaPipe 资源。"""
        self.hands.close()
