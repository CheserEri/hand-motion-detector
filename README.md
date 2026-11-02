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

## 环境要求

- Python 3.8+
- 摄像头设备（内置或外接 USB 摄像头）
- Windows / macOS / Linux

## 安装

```bash
# 克隆仓库
git clone https://github.com/CheserEri/hand-motion-detector.git
cd hand-motion-detector

# 创建虚拟环境（推荐）
python -m venv .venv

# 激活虚拟环境
# Windows:
.venv\Scripts\activate
# macOS/Linux:
source .venv/bin/activate

# 安装依赖
pip install -r requirements.txt
```

## 使用

```bash
python main.py
```

启动后会打开 GUI 界面：

1. **启动摄像头** — 程序自动开启默认摄像头（device_id: 0）
2. **手势识别** — 将手置于摄像头前，系统自动检测并显示手部关键点
3. **查看状态** — 右侧面板实时显示当前运动状态、方向和速度
4. **调整参数** — 点击"设置"按钮可调整检测灵敏度

### 支持的手势

| 手势 | 动作说明 |
|------|----------|
| 👋 挥手 | 手掌左右快速往复移动 |
| ✊ 抓取 | 握拳动作 |
| 🖐 释放 | 张开手掌 |
| ⬆⬇⬅➡ 滑动 | 手指向上下左右移动 |
| ⭕ 画圈 | 指尖画圆形轨迹 |

### 操作按钮

- **显示/隐藏轨迹** — 切换运动轨迹可视化
- **清除轨迹** — 清空当前轨迹记录
- **截图** — 保存当前画面到 `screenshots/` 目录
- **设置** — 调整检测参数（实时生效）

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

## 安卓 App（手势控制）

`android/` 目录是一套完整的 Gradle 工程，可直接用 Android Studio 打开。目标是在 App 运行期间用手势全局控制手机，强调**低功耗**与**高识别率**。

### 手势映射

| 手势 | 动作 |
|------|------|
| 食指尖自下向上（手保持张开） | 在屏幕中央注入上滑 |
| 握拳 | 返回桌面 |
| 伸出 1 / 3 / 4 根手指（拇指收拢，保持约 0.4 秒） | 打开 App 内为该手势指定的应用 |
| 比 ✌（食指、中指伸出，其余收拢） | 切换其他手势的开启 / 关闭 |

只识别向上挥动，向下挥动视为预备/收手动作，不会触发任何手势。张开手掌不再绑定动作，只用于复位握拳状态（握拳后张开再握拳可再次触发）；张开手掌拇指外伸，不会误判成「4 根手指」。手向上移动默认对应系统上滑，可在 App 内反转上下方向。手指手势需在「手势设置 → 手指手势打开应用」中分别选择目标应用，且只在手停稳时识别。比 ✌ 需保持约 0.4 秒才切换一次，保持姿势不会反复切换；悬浮球会显示「手势已开启 / 手势已关闭」。

### 识别与功耗设计

- **采集**：CameraX `ImageAnalysis`，320×240、约 15fps，`KEEP_ONLY_LATEST`，不做预览渲染。
- **推理**：MediaPipe `HandLandmarker` LIVE_STREAM 模式，单手，优先 GPU，失败自动回退 CPU。
- **降频**：无手 1 秒后降至约 8fps；灭屏自动停止摄像头；电量低于 15% 自动暂停。
- **手势切割**：触发一次手势后立即进入未武装状态，必须同时满足 600ms 静默与手部真正停稳（300ms 窗口内累计路程小于 0.15 手长，来回摆动不算停稳）才重新武装；手未回撤/停稳前不会识别下一个手势。连续丢手 5 帧后复位，重新出现的手也需先停稳。
- **意图判定**：只识别向上的竖直挥动，在最近 500ms 内寻找幅度最大、速度最快且方向一致的那一段（≥0.25 手长、≥1.2 手长/秒、同向占比 ≥75%），轻快地上挥一下即可触发；慢速预备动作与向下收手不触发。反向位移超过主挥动 100% 时视为意图不明确，直接忽略。方向只区分上下，左右位移不参与识别。
- **稳定性**：手尺度归一化（腕点→中指 MCP）、One-Euro 滤波、食指尖竖直位移 + 掌心同向移动 + 手指张开度联合判定、握拳使用弯曲角滞回与稳定帧数。
- **无画圈手势**（移植首版不实现）。

### 构建步骤

1. 使用 Android Studio（Ladybug 或更新版本）打开 `android/` 目录，等待 Gradle 同步。
2. 模型文件已放在 `android/app/src/main/assets/hand_landmarker.task`。若缺失，可从
   `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
   下载后放入该目录。
3. 连接手机，运行 `app`。
4. 在 App 内依次授权：相机、通知（Android 13+）、悬浮窗（可选）、无障碍服务（必需），然后点击「启动手势控制」。
5. 手势引擎的单元测试位于 `android/app/src/test/`，可在 Android Studio 中直接运行。

### 限制与提示

- 无障碍服务必须由用户在系统设置中手动开启；本应用不读取屏幕内容，只注入手势。
- Android 14 要求相机前台服务从可见界面启动，因此请从 App 内按钮启动。
- 部分厂商系统会清理后台，建议将本应用加入电池优化白名单。
- 所有识别均在本地完成，画面不保存、不上传。

### 安卓工程结构

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/hand_landmarker.task        # MediaPipe 手部模型
        ├── res/                               # 主题、图标、无障碍配置
        └── java/com/chesereri/handmotiondetector/
            ├── MainActivity.kt                # Compose 权限引导与设置
            ├── service/GestureCameraService.kt    # 前台摄像头服务
            ├── service/HandAccessibilityService.kt# 全局手势注入
            ├── overlay/OverlayBadge.kt        # 可拖动状态球
            ├── vision/GestureEngine.kt        # 纯 Kotlin 手势识别
            ├── vision/HandLandmarkerHelper.kt # MediaPipe 封装
            ├── vision/OneEuroFilter.kt        # 关键点平滑
            └── vision/ImageProxyExt.kt        # 相机帧转 Bitmap
```
