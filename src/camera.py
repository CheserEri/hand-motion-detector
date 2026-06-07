"""摄像头采集模块：线程化视频捕获，避免阻塞 GUI。"""

import threading
from typing import Optional, Tuple

import cv2
import numpy as np


class Camera:
    """线程化摄像头采集器。"""

    def __init__(
        self,
        device_id: int = 0,
        width: int = 640,
        height: int = 480,
        fps: int = 30,
    ):
        self.device_id = device_id
        self.width = width
        self.height = height
        self.fps = fps

        self._cap: Optional[cv2.VideoCapture] = None
        self._frame: Optional[np.ndarray] = None
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None

    def start(self) -> bool:
        """打开摄像头并启动采集线程。返回是否成功。"""
        self._cap = cv2.VideoCapture(self.device_id)
        if not self._cap.isOpened():
            return False

        self._cap.set(cv2.CAP_PROP_FRAME_WIDTH, self.width)
        self._cap.set(cv2.CAP_PROP_FRAME_HEIGHT, self.height)
        self._cap.set(cv2.CAP_PROP_FPS, self.fps)

        self._running = True
        self._thread = threading.Thread(target=self._capture_loop, daemon=True)
        self._thread.start()
        return True

    def stop(self):
        """停止采集并释放摄像头。"""
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None
        if self._cap is not None:
            self._cap.release()
            self._cap = None

    def read(self) -> Optional[np.ndarray]:
        """读取最新一帧（线程安全）。返回 BGR 图像或 None。"""
        with self._lock:
            if self._frame is None:
                return None
            return self._frame.copy()

    def is_running(self) -> bool:
        return self._running

    def _capture_loop(self):
        """后台采集循环。"""
        while self._running:
            if self._cap is None or not self._cap.isOpened():
                break
            ret, frame = self._cap.read()
            if not ret:
                continue
            frame = cv2.flip(frame, 1)  # 镜像翻转
            with self._lock:
                self._frame = frame
