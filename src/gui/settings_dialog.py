"""设置对话框：调整检测参数。"""

import os
from typing import Optional

import yaml
from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import (
    QDialog,
    QDoubleSpinBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QPushButton,
    QVBoxLayout,
    QWidget,
)


class SettingsDialog(QDialog):
    """参数设置对话框。"""

    def __init__(self, config: dict, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWindowTitle("设置")
        self.setMinimumWidth(400)
        self.config = config
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout()
        motion_cfg = self.config.get("motion", {})

        # --- 运动检测参数 ---
        motion_group = QGroupBox("运动检测参数")
        form = QFormLayout()

        self._dir_threshold = self._make_spin(motion_cfg.get("direction_threshold", 3.0), 0.5, 20.0, 0.5)
        form.addRow("方向判定阈值 (像素/帧):", self._dir_threshold)

        self._smoothing = self._make_spin(motion_cfg.get("smoothing_window", 5), 1, 20, 1)
        form.addRow("速度平滑窗口 (帧):", self._smoothing)

        self._wave_changes = self._make_spin(motion_cfg.get("wave_min_changes", 3), 2, 10, 1)
        form.addRow("挥手最小方向变化:", self._wave_changes)

        self._wave_window = self._make_spin(motion_cfg.get("wave_time_window", 1.5), 0.5, 5.0, 0.1)
        form.addRow("挥手检测时间窗口 (秒):", self._wave_window)

        self._grab_threshold = self._make_spin(motion_cfg.get("grab_bend_threshold", 60), 20, 120, 5)
        form.addRow("抓取弯曲角度阈值 (°):", self._grab_threshold)

        self._swipe_speed = self._make_spin(motion_cfg.get("click_speed_threshold", 15.0), 5.0, 50.0, 1.0)
        form.addRow("滑动速度阈值:", self._swipe_speed)

        self._circle_min_pts = self._make_spin(motion_cfg.get("circle_min_points", 15), 5, 50, 1)
        form.addRow("画圈最少轨迹点:", self._circle_min_pts)

        self._circle_fit = self._make_spin(motion_cfg.get("circle_fit_threshold", 0.25), 0.05, 1.0, 0.05)
        form.addRow("画圈拟合误差阈值:", self._circle_fit)

        motion_group.setLayout(form)
        layout.addWidget(motion_group)

        # --- 检测参数 ---
        det_cfg = self.config.get("detection", {})
        det_group = QGroupBox("检测参数")
        det_form = QFormLayout()

        self._det_conf = self._make_spin(det_cfg.get("detection_confidence", 0.7), 0.1, 1.0, 0.05)
        det_form.addRow("检测置信度:", self._det_conf)

        self._track_conf = self._make_spin(det_cfg.get("tracking_confidence", 0.5), 0.1, 1.0, 0.05)
        det_form.addRow("跟踪置信度:", self._track_conf)

        det_group.setLayout(det_form)
        layout.addWidget(det_group)

        # --- 按钮 ---
        btn_layout = QHBoxLayout()
        save_btn = QPushButton("保存到文件")
        save_btn.setToolTip("将当前参数写入 config.yaml")
        save_btn.clicked.connect(self._on_save_to_file)
        ok_btn = QPushButton("确定")
        ok_btn.clicked.connect(self._on_ok)
        cancel_btn = QPushButton("取消")
        cancel_btn.clicked.connect(self.reject)
        btn_layout.addWidget(save_btn)
        btn_layout.addStretch()
        btn_layout.addWidget(ok_btn)
        btn_layout.addWidget(cancel_btn)
        layout.addLayout(btn_layout)

        self.setLayout(layout)

    def _make_spin(self, value: float, min_val: float, max_val: float, step: float) -> QDoubleSpinBox:
        spin = QDoubleSpinBox()
        spin.setRange(min_val, max_val)
        spin.setSingleStep(step)
        spin.setValue(value)
        return spin

    def _sync_ui_to_config(self):
        """将界面值写回 config 字典。"""
        motion = self.config.setdefault("motion", {})
        motion["direction_threshold"] = self._dir_threshold.value()
        motion["smoothing_window"] = int(self._smoothing.value())
        motion["wave_min_changes"] = int(self._wave_changes.value())
        motion["wave_time_window"] = self._wave_window.value()
        motion["grab_bend_threshold"] = self._grab_threshold.value()
        motion["click_speed_threshold"] = self._swipe_speed.value()
        motion["circle_min_points"] = int(self._circle_min_pts.value())
        motion["circle_fit_threshold"] = self._circle_fit.value()

        det = self.config.setdefault("detection", {})
        det["detection_confidence"] = self._det_conf.value()
        det["tracking_confidence"] = self._track_conf.value()

    def _on_ok(self):
        """将界面值写回 config 字典并关闭。"""
        self._sync_ui_to_config()
        self.accept()

    def _on_save_to_file(self):
        """将当前参数保存到 config.yaml 文件。"""
        self._sync_ui_to_config()
        config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "config.yaml")
        config_path = os.path.normpath(config_path)
        try:
            with open(config_path, "w", encoding="utf-8") as f:
                yaml.dump(self.config, f, allow_unicode=True, default_flow_style=False)
            from PyQt5.QtWidgets import QMessageBox
            QMessageBox.information(self, "保存成功", f"配置已保存到:\n{config_path}")
        except Exception as e:
            from PyQt5.QtWidgets import QMessageBox
            QMessageBox.warning(self, "保存失败", f"写入配置文件失败:\n{e}")
