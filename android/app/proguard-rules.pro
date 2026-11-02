# MediaPipe Tasks
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# MediaPipe 依赖里带 AutoValue 注解处理器，编译期类不需要打包
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# 无障碍服务由系统通过 XML 实例化
-keep class com.chesereri.handmotiondetector.service.HandAccessibilityService { *; }
