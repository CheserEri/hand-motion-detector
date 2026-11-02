package com.chesereri.handmotiondetector

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chesereri.handmotiondetector.service.GestureCameraService
import com.chesereri.handmotiondetector.settings.AppSettings
import com.chesereri.handmotiondetector.ui.theme.HandMotionTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)

        setContent {
            HandMotionTheme {
                val context = LocalContext.current
                var permissions by remember { mutableStateOf(readPermissions(context)) }
                var running by remember { mutableStateOf(GestureCameraService.isRunning) }
                var overlayEnabled by remember { mutableStateOf(settings.overlayEnabled) }
                var invertVertical by remember { mutableStateOf(settings.invertVertical) }
                var appOne by remember { mutableStateOf(settings.appForFingerOne) }
                var appThree by remember { mutableStateOf(settings.appForFingerThree) }
                var appFour by remember { mutableStateOf(settings.appForFingerFour) }
                var pickerFor by remember { mutableStateOf<Int?>(null) }
                val installedApps = remember { loadLaunchableApps(context) }

                fun refresh() {
                    permissions = readPermissions(context)
                    running = GestureCameraService.isRunning
                    overlayEnabled = settings.overlayEnabled
                    invertVertical = settings.invertVertical
                    appOne = settings.appForFingerOne
                    appThree = settings.appForFingerThree
                    appFour = settings.appForFingerFour
                }

                val cameraLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { refresh() }
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { refresh() }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) refresh()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                SetupScreen(
                    permissions = permissions,
                    running = running,
                    overlayEnabled = overlayEnabled,
                    invertVertical = invertVertical,
                    appOneLabel = appLabel(context, appOne),
                    appThreeLabel = appLabel(context, appThree),
                    appFourLabel = appLabel(context, appFour),
                    onCameraAction = {
                        if (permissions.camera) {
                            openSettingsSafely(context, appDetailsIntent(context))
                        } else {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onNotificationAction = {
                        if (permissions.notifications) {
                            openSettingsSafely(context, appDetailsIntent(context))
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenOverlay = {
                        openSettingsSafely(
                            context,
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    onOpenAccessibility = {
                        openSettingsSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenBatterySettings = {
                        openSettingsSafely(
                            context,
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    },
                    onToggleOverlay = { enabled ->
                        settings.overlayEnabled = enabled
                        overlayEnabled = enabled
                        GestureCameraService.refreshSettings()
                    },
                    onToggleInvertVertical = { enabled ->
                        settings.invertVertical = enabled
                        invertVertical = enabled
                        GestureCameraService.refreshSettings()
                    },
                    onPickApp = { fingerCount -> pickerFor = fingerCount },
                    onStart = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, GestureCameraService::class.java),
                        )
                        running = true
                    },
                    onStop = {
                        context.stopService(Intent(context, GestureCameraService::class.java))
                        running = false
                    },
                )

                val target = pickerFor
                if (target != null) {
                    AppPickerDialog(
                        apps = installedApps,
                        onSelect = { packageName ->
                            when (target) {
                                1 -> {
                                    settings.appForFingerOne = packageName
                                    appOne = packageName
                                }
                                3 -> {
                                    settings.appForFingerThree = packageName
                                    appThree = packageName
                                }
                                else -> {
                                    settings.appForFingerFour = packageName
                                    appFour = packageName
                                }
                            }
                            GestureCameraService.refreshSettings()
                            pickerFor = null
                        },
                        onDismiss = { pickerFor = null },
                    )
                }
            }
        }
    }
}

private data class PermissionState(
    val camera: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val accessibility: Boolean,
)

private fun readPermissions(context: Context): PermissionState {
    val camera = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    return PermissionState(
        camera = camera,
        notifications = notifications,
        overlay = Settings.canDrawOverlays(context),
        accessibility = isAccessibilityServiceEnabled(context),
    )
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

private fun appDetailsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

private fun openSettingsSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (t: Throwable) {
        context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SetupScreen(
    permissions: PermissionState,
    running: Boolean,
    overlayEnabled: Boolean,
    invertVertical: Boolean,
    appOneLabel: String?,
    appThreeLabel: String?,
    appFourLabel: String?,
    onCameraAction: () -> Unit,
    onNotificationAction: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onToggleInvertVertical: (Boolean) -> Unit,
    onPickApp: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(scheme.primary, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "LOCAL VISION  /  HUD",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                )
            }
            Text("手势控制", style = MaterialTheme.typography.headlineMedium)
            Text(
                "摄像头识别在本地完成，画面不保存、不上传",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }

        StatusCard(running)

        SectionLabel("01", "启动条件")
        HudCard {
            PermissionRow(
                title = "相机",
                description = "后台识别手部关键点，画面不保存或上传",
                granted = permissions.camera,
                onAction = onCameraAction,
            )
            HudDivider()
            PermissionRow(
                title = "通知",
                description = "显示常驻通知，降低后台被系统回收的概率",
                granted = permissions.notifications,
                onAction = onNotificationAction,
            )
            HudDivider()
            PermissionRow(
                title = "悬浮窗",
                description = "在屏幕上显示识别状态球（可选）",
                granted = permissions.overlay,
                onAction = onOpenOverlay,
            )
            HudDivider()
            PermissionRow(
                title = "无障碍",
                description = "将手势转换为全局滑动、返回等操作（必需）",
                granted = permissions.accessibility,
                onAction = onOpenAccessibility,
            )
        }

        SectionLabel("02", "手势")
        HudCard {
            GestureRow(title = "向上挥动", target = "上滑 · 下一条")
            HudDivider()
            GestureRow(title = "握拳", target = "返回桌面")
            HudDivider()
            Text(
                "手指手势 → 打开应用",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            AppPickerRow(
                title = "伸出 1 根手指",
                appName = appOneLabel,
                onPick = { onPickApp(1) },
            )
            HudDivider()
            AppPickerRow(
                title = "伸出 3 根手指",
                appName = appThreeLabel,
                onPick = { onPickApp(3) },
            )
            HudDivider()
            AppPickerRow(
                title = "伸出 4 根手指",
                appName = appFourLabel,
                onPick = { onPickApp(4) },
            )
            HudDivider()
            GestureRow(title = "比 ✌", target = "开启 / 关闭其他手势")
        }

        SectionLabel("03", "偏好")
        HudCard {
            SwitchRow(
                title = "显示悬浮状态球",
                checked = overlayEnabled,
                onCheckedChange = onToggleOverlay,
            )
            HudDivider()
            SwitchRow(
                title = "上滑方向反转",
                checked = invertVertical,
                onCheckedChange = onToggleInvertVertical,
            )
        }

        Spacer(Modifier.height(2.dp))

        if (running) {
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A1214),
                    contentColor = scheme.error,
                ),
                border = BorderStroke(1.dp, Color(0xFF5C2A2E)),
            ) {
                Text(
                    "停止手势控制",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Button(
                onClick = onStart,
                enabled = permissions.camera && permissions.accessibility,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    disabledContainerColor = scheme.surfaceVariant,
                    disabledContentColor = scheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    "启动手势控制",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (!permissions.camera || !permissions.accessibility) {
                Text(
                    buildString {
                        if (!permissions.camera) append("缺少相机权限")
                        if (!permissions.camera && !permissions.accessibility) append(" · ")
                        if (!permissions.accessibility) append("缺少无障碍服务")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.error,
                )
            }
        }

        Text(
            "Android 13+ 需要通知权限；部分厂商系统建议加入电池优化白名单。" +
                "灭屏自动停摄像头，电量低于 15% 自动暂停。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenBatterySettings) {
            Text(
                "打开电池优化设置",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
        }
    }
}

/** 细描边深色卡片，HUD 风格不使用阴影与渐变。 */
@Composable
private fun HudCard(content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
private fun HudDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SectionLabel(index: String, title: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            index,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GestureRow(title: String, target: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            target,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppPickerRow(
    title: String,
    appName: String?,
    onPick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                appName ?: "未设置",
                style = MaterialTheme.typography.labelSmall,
                color = if (appName != null) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onPick) {
            Text(
                if (appName != null) "更换" else "选择",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
            )
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppEntry>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.outline, RoundedCornerShape(14.dp)),
        ) {
            Text(
                "选择要打开的应用",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            HudDivider()
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(app.packageName) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            HudDivider()
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 8.dp),
            ) {
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class AppEntry(val packageName: String, val label: String)

/** 读取所有可启动的应用，供手指手势绑定。 */
private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            AppEntry(packageName, info.loadLabel(pm).toString())
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label }
}

/** 已保存包名对应的应用名称；未设置返回 null。 */
private fun appLabel(context: Context, packageName: String): String? {
    if (packageName.isEmpty()) return null
    val pm = context.packageManager
    return try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        "应用已卸载"
    }
}

@Composable
private fun StatusCard(running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    HudCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        color = if (running) scheme.primary else scheme.outline,
                        shape = CircleShape,
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "手势控制运行中" else "手势控制未启动",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (running) {
                        "后台约 15fps，无手时自动降至约 8fps"
                    } else {
                        "按下方按钮启动，识别全程在本地完成"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (running) "ACTIVE" else "STANDBY",
                style = MaterialTheme.typography.labelMedium,
                color = if (running) scheme.primary else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (granted) "OK" else "待开通",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) scheme.primary else scheme.error,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (granted) scheme.primaryContainer else Color(0xFF2A1214),
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        TextButton(onClick = onAction) {
            Text(
                if (granted) "设置" else "去开启",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}
