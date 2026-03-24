package com.livelens.translator.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.livelens.translator.model.TranslationMode
import com.livelens.translator.ui.overlay.FloatingOverlayController
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Accessibility service that manages the floating overlay window.
 *
 * Lifecycle:
 *  - onServiceConnected: Sets up the overlay window and registers command receivers
 *  - onAccessibilityEvent: Not used for translation logic, only to maintain context
 *  - onUnbind / onDestroy: Removes overlay window
 *
 * The overlay is managed by [FloatingOverlayController], which owns the
 * floating bubble and subtitle bar Compose views.
 */
@AndroidEntryPoint
class TranslatorAccessibilityService : AccessibilityService() {

    @Inject lateinit var translationManager: TranslationManager

    private var overlayController: FloatingOverlayController? = null
    private var windowManager: WindowManager? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Timber.d("commandReceiver.onReceive: action=${intent.action}")
            when (intent.action) {
                ACTION_START_CONVERSATION -> {
                    Timber.i("→ ACTION_START_CONVERSATION nhận được — gọi showAndSetMode(CONVERSATION)")
                    overlayController?.showAndSetMode(TranslationMode.CONVERSATION)
                        ?: Timber.e("overlayController = null khi nhận ACTION_START_CONVERSATION!")
                }
                ACTION_START_MEDIA       -> {
                    Timber.i("→ ACTION_START_MEDIA nhận được — gọi showAndSetMode(MEDIA)")
                    overlayController?.showAndSetMode(TranslationMode.MEDIA)
                        ?: Timber.e("overlayController = null khi nhận ACTION_START_MEDIA!")
                }
                ACTION_START_IMAGE       -> {
                    Timber.i("→ ACTION_START_IMAGE nhận được — gọi showAndSetMode(IMAGE)")
                    overlayController?.showAndSetMode(TranslationMode.IMAGE)
                        ?: Timber.e("overlayController = null khi nhận ACTION_START_IMAGE!")
                }
                ACTION_STOP_OVERLAY      -> {
                    Timber.i("→ ACTION_STOP_OVERLAY nhận được — gọi hide()")
                    overlayController?.hide()
                }
                ACTION_SERVICE_STATUS    -> {
                    Timber.d("→ ACTION_SERVICE_STATUS nhận được — phản hồi trạng thái")
                    sendStatusBroadcast()
                }
                else -> Timber.w("commandReceiver nhận action không biết: '${intent.action}'")
            }
        }
    }

    companion object {
        const val ACTION_START_CONVERSATION = "com.livelens.overlay.START_CONVERSATION"
        const val ACTION_START_MEDIA        = "com.livelens.overlay.START_MEDIA"
        const val ACTION_START_IMAGE        = "com.livelens.overlay.START_IMAGE"
        const val ACTION_STOP_OVERLAY       = "com.livelens.overlay.STOP"
        const val ACTION_SERVICE_STATUS     = "com.livelens.overlay.STATUS_REQUEST"
        const val ACTION_SERVICE_STATUS_REPLY = "com.livelens.overlay.STATUS_REPLY"
        const val EXTRA_SERVICE_RUNNING     = "is_running"

        /** Check if accessibility service is enabled */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(
                "${context.packageName}/${TranslatorAccessibilityService::class.java.name}"
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("━━━ TranslatorAccessibilityService.onServiceConnected() ━━━ PID=${android.os.Process.myPid()}")

        // Configure which accessibility events we care about (minimal)
        try {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 100
            }
            Timber.d("AccessibilityServiceInfo cấu hình thành công ✓")
        } catch (e: Exception) {
            Timber.e(e, "Cấu hình AccessibilityServiceInfo THẤT BẠI ✗")
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Timber.d("WindowManager lấy: ${if (windowManager != null) "OK ✓" else "NULL ✗"}")

        setupOverlay()
        registerCommandReceiver()
    }

    private fun setupOverlay() {
        val wm = windowManager ?: run {
            Timber.e("setupOverlay() — windowManager = null, bỏ qua!")
            return
        }
        // Kiểm tra quyền overlay
        val hasOverlayPermission = android.provider.Settings.canDrawOverlays(this)
        Timber.d("SYSTEM_ALERT_WINDOW (overlay) permission: ${if (hasOverlayPermission) "GRANTED ✓" else "DENIED ✗"}")
        if (!hasOverlayPermission) {
            Timber.e("Không có quyền overlay (SYSTEM_ALERT_WINDOW) — FloatingOverlayController sẽ không hoạt động!")
        }

        try {
            Timber.d("Tạo FloatingOverlayController...")
            overlayController = FloatingOverlayController(
                context = this,
                windowManager = wm,
                translationManager = translationManager
            )
            overlayController?.create()
            Timber.i("FloatingOverlayController tạo thành công ✓ overlayController=$overlayController")
        } catch (e: Exception) {
            Timber.e(e, "FloatingOverlayController tạo THẤT BẠI ✗")
        }
    }

    private fun registerCommandReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_START_CONVERSATION)
            addAction(ACTION_START_MEDIA)
            addAction(ACTION_START_IMAGE)
            addAction(ACTION_STOP_OVERLAY)
            addAction(ACTION_SERVICE_STATUS)
        }
        try {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
            Timber.d("BroadcastReceiver đăng ký thành công ✓ — lắng nghe các action overlay")
        } catch (e: Exception) {
            Timber.e(e, "Đăng ký BroadcastReceiver THẤT BẠI ✗")
        }
    }

    private fun sendStatusBroadcast() {
        val reply = Intent(ACTION_SERVICE_STATUS_REPLY).apply {
            putExtra(EXTRA_SERVICE_RUNNING, true)
        }
        sendBroadcast(reply)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for translation logic
    }

    override fun onInterrupt() {
        Timber.w("AccessibilityService.onInterrupt() — service bị gián đoạn bởi hệ thống")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.i("TranslatorAccessibilityService.onUnbind() — service bị ngắt kết nối")
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        Timber.i("TranslatorAccessibilityService.onDestroy() — service đã hủy")
    }

    private fun cleanup() {
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        overlayController?.destroy()
        overlayController = null
    }
}
