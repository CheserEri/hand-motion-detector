"""主窗口：摄像头画面 + 控制面板。"""

import time
from typing import Optional

import cv2
import numpy as np
from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtGui import QFont
from PyQt5.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFrame,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QPushButton,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from src.camera import Camera
from src.gui.canvas import VideoCanvas
from src.gui.settings_dialog import SettingsDialog
from src.hand_detector import HandDetector
from src.motion_analyzer import MotionAnalyzer, MotionEvent, MotionType, MotionState
from src.trajectory import TrajectoryRecorder


# 动作类型对应的中文名称、图标和主题色 (背景色, 前景色)
MOTION_DISPLAY = {
    MotionType.IDLE:        ("静止", "⏸", "#2b2b2b", "#888888"),
    MotionType.MOVING:      ("移动", "✋", "#1b2b3b", "#44aaff"),
    MotionType.WAVING:      ("挥手", "👋", "#2b2b1b", "#ffaa00"),
    MotionType.GRABBING:    ("抓取", "✊", "#2b1b1b", "#ff4444"),
    MotionType.RELEASING:   ("释放", "🖐", "#1b2b1b", "#44ff44"),
    MotionType.CIRCLE:      ("画圈", "⭕", "#2b1b2b", "#ff44ff"),
    MotionType.SWIPE_UP:    ("上滑", "⬆", "#1b2b2b", "#00ffaa"),
    MotionType.SWIPE_DOWN:  ("下滑", "⬇", "#1b2b2b", "#00aaff"),
    MotionType.SWIPE_LEFT:  ("左滑", "⬅", "#2b2b1b", "#ffff00"),
    MotionType.SWIPE_RIGHT: ("右滑", "➡", "#2b1b2b", "#ff8800"),
}


class MainWindow(QMainWindow):
    """手部运动检测主窗口。"""

    def __init__(self, config: dict):
        super().__init__()
        self.config = config
        self.setWindowTitle("手部运动检测")
        self.setMinimumSize(1000, 600)

        # 核心组件
        self._camera: Optional[Camera] = None
        self._detector: Optional[HandDetector] = None
        self._analyzer: Optional[MotionAnalyzer] = None
        self._trajectory: Optional[TrajectoryRecorder] = None

        # 帧计数和 FPS
        self._frame_count = 0
        self._fps_time = time.time()
        self._current_fps = 0.0

        self._init_ui()
        self._init_processing()

        # 定时器驱动画面更新
        self._timer = QTimer()
        self._timer.timeout.connect(self._process_frame)
        self._timer.start(33)  # ~30fps

    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QHBoxLayout()
        central.setLayout(main_layout)

        # --- 左侧：视频画面 ---
        self._canvas = VideoCanvas()
        main_layout.addWidget(self._canvas, stretch=3)

        # --- 右侧：控制面板 ---
        right_panel = QWidget()
        right_layout = QVBoxLayout()
        right_panel.setLayout(right_layout)
        right_panel.setMaximumWidth(320)
        main_layout.addWidget(right_panel, stretch=1)

        # FPS 显示
        self._fps_label = QLabel("FPS: --")
        self._fps_label.setFont(QFont("Consolas", 10))
        right_layout.addWidget(self._fps_label)

        # 当前动作显示
        motion_group = QGroupBox("当前状态")
        motion_layout = QVBoxLayout()
        self._motion_label = QLabel("等待检测...")
        self._motion_label.setFont(QFont("Microsoft YaHei", 16, QFont.Bold))
        self._motion_label.setAlignment(Qt.AlignCenter)
        self._motion_label.setStyleSheet(
            "padding: 15px; background-color: #2b2b2b; border-radius: 8px; color: #00ff88;"
        )
        motion_layout.addWidget(self._motion_label)

        self._direction_label = QLabel("方向: --")
        self._direction_label.setFont(QFont("Microsoft YaHei", 11))
        self._direction_label.setAlignment(Qt.AlignCenter)
        motion_layout.addWidget(self._direction_label)

        self._speed_label = QLabel("速度: --")
        self._speed_label.setFont(QFont("Microsoft YaHei", 11))
        self._speed_label.setAlignment(Qt.AlignCenter)
        motion_layout.addWidget(self._speed_label)
        motion_group.setLayout(motion_layout)
        right_layout.addWidget(motion_group)

        # 事件日志
        log_group = QGroupBox("事件日志")
        log_layout = QVBoxLayout()
        self._log_text = QTextEdit()
        self._log_text.setReadOnly(True)
        self._log_text.setFont(QFont("Consolas", 9))
        self._log_text.setMaximumHeight(180)
        log_layout.addWidget(self._log_text)
        log_group.setLayout(log_layout)
        right_layout.addWidget(log_group)

        # 控制按钮
        ctrl_group = QGroupBox("控制")
        ctrl_layout = QVBoxLayout()

        self._trajectory_check = QCheckBox("显示运动轨迹")
        self._trajectory_check.setChecked(self.config.get("trajectory", {}).get("enabled", True))
        self._trajectory_check.stateChanged.connect(self._toggle_trajectory)
        ctrl_layout.addWidget(self._trajectory_check)

        self._landmark_check = QCheckBox("显示关键点")
        self._landmark_check.setChecked(True)
        ctrl_layout.addWidget(self._landmark_check)

        btn_clear = QPushButton("清除轨迹")
        btn_clear.clicked.connect(self._clear_trajectory)
        ctrl_layout.addWidget(btn_clear)

        btn_settings = QPushButton("参数设置")
        btn_settings.clicked.connect(self._open_settings)
        ctrl_layout.addWidget(btn_settings)

        btn_reset = QPushButton("重置检测")
        btn_reset.clicked.connect(self._reset_analyzer)
        ctrl_layout.addWidget(btn_reset)

        btn_screenshot = QPushButton("截图保存")
        btn_screenshot.clicked.connect(self._save_screenshot)
        ctrl_layout.addWidget(btn_screenshot)

        ctrl_group.setLayout(ctrl_layout)
        right_layout.addWidget(ctrl_group)

        right_layout.addStretch()

    def _init_processing(self):
        """初始化各处理模块。"""
        cam_cfg = self.config.get("camera", {})
        self._camera = Camera(
            device_id=cam_cfg.get("device_id", 0),
            width=cam_cfg.get("width", 640),
            height=cam_cfg.get("height", 480),
            fps=cam_cfg.get("fps", 30),
        )

        det_cfg = self.config.get("detection", {})
        self._detector = HandDetector(
            max_hands=det_cfg.get("max_hands", 1),
            detection_confidence=det_cfg.get("detection_confidence", 0.7),
            tracking_confidence=det_cfg.get("tracking_confidence", 0.5),
        )

        self._analyzer = MotionAnalyzer(self.config)

        traj_cfg = self.config.get("trajectory", {})
        self._trajectory = TrajectoryRecorder(
            max_length=traj_cfg.get("max_length", 120),
            line_width=traj_cfg.get("line_width", 2),
            fade_speed=traj_cfg.get("fade_speed", 5),
        )
        self._trajectory.enabled = traj_cfg.get("enabled", True)

        if not self._camera.start():
            self._motion_label.setText("⚠ 摄像头打开失败")
            self._motion_label.setStyleSheet(
                "padding: 15px; background-color: #3b1b1b; border-radius: 8px; color: #ff4444;"
            )

    def _process_frame(self):
        """处理一帧画面（定时器回调）。"""
        try:
            self._process_frame_inner()
        except Exception as e:
            print(f"[错误] 帧处理异常: {e}")

    def _process_frame_inner(self):
        if self._camera is None or not self._camera.is_running():
            return

        frame = self._camera.read()
        if frame is None:
            return

        self._frame_count += 1
        now = time.time()
        if now - self._fps_time >= 1.0:
            self._current_fps = self._frame_count / (now - self._fps_time)
            self._frame_count = 0
            self._fps_time = now
            self._fps_label.setText(f"FPS: {self._current_fps:.1f}")

        # 手部检测
        hands = self._detector.detect(frame)
        display = frame  # camera.read() 已返回副本，无需再次 copy

        if hands:
            hand = hands[0]  # 取第一只手

            # 绘制关键点
            if self._landmark_check.isChecked():
                display = self._detector.draw_landmarks(display, hand)

            # 更新轨迹
            self._trajectory.update(hand.landmarks)

            # 运动分析
            state = self._analyzer.analyze(hand)
            self._update_motion_display(state)

            # 处理事件
            for event in state.events:
                self._log_event(event)
        else:
            self._trajectory.update(None)
            self._motion_label.setText("未检测到手部")
            self._direction_label.setText("方向: --")
            self._speed_label.setText("速度: --")

        # 绘制轨迹
        display = self._trajectory.draw(display)

        self._canvas.update_frame(display)

    def _update_motion_display(self, state: MotionState):
        """更新运动状态显示。"""
        display = MOTION_DISPLAY.get(state.current_motion)
        if display:
            label, icon, bg, fg = display
        else:
            label, icon, bg, fg = "未知", "?", "#2b2b2b", "#00ff88"
        self._motion_label.setText(f"{icon} {label}")
        self._direction_label.setText(f"方向: {state.direction}")
        self._speed_label.setText(f"速度: {state.speed:.1f} px/帧")
        self._motion_label.setStyleSheet(
            f"padding: 15px; background-color: {bg}; border-radius: 8px; color: {fg};"
        )

    def _log_event(self, event: MotionEvent):
        """将事件写入日志。"""
        ts = time.strftime("%H:%M:%S", time.localtime(event.timestamp))
        self._log_text.append(f"[{ts}] {event.description}")

        # 限制日志行数
        doc = self._log_text.document()
        if doc.blockCount() > 50:
            cursor = self._log_text.textCursor()
            cursor.movePosition(cursor.Start)
            cursor.select(cursor.BlockUnderCursor)
            cursor.removeSelectedText()

    def _toggle_trajectory(self, state: int):
        self._trajectory.enabled = (state == Qt.Checked)

    def _clear_trajectory(self):
        self._trajectory.clear()

    def _reset_analyzer(self):
        self._analyzer.reset()
        self._log_text.append("[系统] 检测状态已重置")

    def _open_settings(self):
        dialog = SettingsDialog(self.config, self)
        if dialog.exec_() == SettingsDialog.Accepted:
            # 重新初始化检测器（置信度可能变了）
            det_cfg = self.config.get("detection", {})
            self._detector.release()
            self._detector = HandDetector(
                max_hands=det_cfg.get("max_hands", 1),
                detection_confidence=det_cfg.get("detection_confidence", 0.7),
                tracking_confidence=det_cfg.get("tracking_confidence", 0.5),
            )
            # 重新初始化分析器
            self._analyzer = MotionAnalyzer(self.config)
            self._log_text.append("[系统] 参数已更新")

    def _save_screenshot(self):
        frame = self._camera.read()
        if frame is None:
            return
        ts = time.strftime("%Y%m%d_%H%M%S")
        default_name = f"screenshot_{ts}.png"
        filename, _ = QFileDialog.getSaveFileName(
            self, "保存截图", default_name, "PNG 图片 (*.png);;JPEG 图片 (*.jpg)"
        )
        if not filename:
            return
        cv2.imwrite(filename, frame)
        self._log_text.append(f"[系统] 截图已保存: {filename}")

    def closeEvent(self, event):
        """窗口关闭时释放资源。"""
        self._timer.stop()
        try:
            if self._camera:
                self._camera.stop()
        except Exception as e:
            print(f"[警告] 摄像头关闭异常: {e}")
        try:
            if self._detector:
                self._detector.release()
        except Exception as e:
            print(f"[警告] 检测器释放异常: {e}")
        event.accept()
