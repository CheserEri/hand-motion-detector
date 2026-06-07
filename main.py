"""手部运动检测 - 程序入口。"""

import sys
import os

import yaml
from PyQt5.QtWidgets import QApplication

from src.gui.main_window import MainWindow


def load_config(config_path: str = "config.yaml") -> dict:
    """加载配置文件。"""
    # 相对于脚本所在目录
    base_dir = os.path.dirname(os.path.abspath(__file__))
    full_path = os.path.join(base_dir, config_path)

    if not os.path.exists(full_path):
        print(f"[警告] 配置文件不存在: {full_path}，使用默认配置")
        return {}

    with open(full_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def main():
    config = load_config()
    app = QApplication(sys.argv)
    app.setStyle("Fusion")

    # 深色主题
    from PyQt5.QtGui import QPalette, QColor
    palette = QPalette()
    palette.setColor(QPalette.Window, QColor(43, 43, 43))
    palette.setColor(QPalette.WindowText, QColor(220, 220, 220))
    palette.setColor(QPalette.Base, QColor(35, 35, 35))
    palette.setColor(QPalette.AlternateBase, QColor(53, 53, 53))
    palette.setColor(QPalette.Text, QColor(220, 220, 220))
    palette.setColor(QPalette.Button, QColor(53, 53, 53))
    palette.setColor(QPalette.ButtonText, QColor(220, 220, 220))
    palette.setColor(QPalette.Highlight, QColor(0, 120, 215))
    app.setPalette(palette)

    window = MainWindow(config)
    window.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
