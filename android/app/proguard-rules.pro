# MediaPipe Tasks
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# 无障碍服务由系统通过 XML 实例化
-keep class com.chesereri.handmotiondetector.service.HandAccessibilityService { *; }
