# 手部运动检测 (Hand Motion Detector)

基于摄像头的实时手部运动状态检测应用。使用 MediaPipe 进行手部关键点检测，通过轨迹分析识别动态手势动作。

## 功能特性

- **实时手部检测** — MediaPipe 21 个关键点追踪
- **动态动作识别**:
  - 👋 挥手（左右快速往复）
  - ✊ 抓取 / 🖐 释放（握拳与张开）
  - ⬆⬇⬅➡ 上/下/左/右滑动
  - ⭕ 画圈（指尖轨迹圆形拟合）
- **运动轨迹可视化** — 彩色渐变轨迹线
- **完整 GUI** — PyQt5 深色主题界面
- **参数可调** — 运行时调整检测灵敏度和阈值
- **截图保存** — 一键保存当前画面

## 安装

```bash
cd hand-motion-detector
pip install -r requirements.txt
```

> 需要 Python 3.8+ 和可用的摄像头设备。

## 运行

```bash
python main.py
```

## 项目结构

```
├── main.py                 # 程序入口
├── config.yaml             # 配置文件
├── requirements.txt        # 依赖列表
├── src/
│   ├── camera.py           # 摄像头采集（线程化）
│   ├── hand_detector.py    # MediaPipe 手部检测封装
│   ├── motion_analyzer.py  # 运动状态分析引擎
│   ├── trajectory.py       # 轨迹记录与可视化
│   └── gui/
│       ├── main_window.py  # 主窗口
│       ├── canvas.py       # 视频画布控件
│       └── settings_dialog.py  # 设置对话框
└── utils/
    └── helpers.py          # 工具函数
```

## 配置说明

编辑 `config.yaml` 调整参数：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `motion.direction_threshold` | 运动方向判定最小速度 | 3.0 px/帧 |
| `motion.smoothing_window` | 速度平滑窗口 | 5 帧 |
| `motion.wave_min_changes` | 挥手最小方向变化次数 | 3 次 |
| `motion.grab_bend_threshold` | 抓取弯曲角度阈值 | 60° |
| `detection.detection_confidence` | 手部检测置信度 | 0.7 |
| `detection.tracking_confidence` | 手部跟踪置信度 | 0.5 |

## 界面说明

- **左侧**: 摄像头实时画面，叠加手部关键点和运动轨迹
- **右侧控制面板**:
  - 当前运动状态（大字显示）
  - 运动方向和速度
  - 事件日志
  - 轨迹显示/清除、参数设置、截图等功能按钮
