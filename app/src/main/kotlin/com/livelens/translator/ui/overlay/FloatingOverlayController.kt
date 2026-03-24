package com.livelens.translator.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.livelens.translator.model.TranslationMode
import com.livelens.translator.service.TranslationManager
import com.livelens.translator.ui.theme.LiveLensTheme
import timber.log.Timber

/**
 * Controller that manages two overlay windows:
 * 1. Floating bubble (collapsed, draggable FAB)
 * 2. Translation card (expanded overlay at bottom of screen)
 *
 * Both are [ComposeView] instances added to [WindowManager].
 * They share a [LifecycleOwner] and [SavedStateRegistryOwner] to support Compose.
 */
class FloatingOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val translationManager: TranslationManager
) : LifecycleOwner, SavedStateRegistryOwner {

    // ─── Lifecycle & saved state support for ComposeView ─────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // ─── Overlay views ────────────────────────────────────────────────────────
    private var bubbleView: View? = null
    private var overlayCardView: View? = null

    private var isExpanded = false
    private var currentMode: TranslationMode = TranslationMode.CONVERSATION

    // Bubble drag state
    private var bubbleDragStartX = 0f
    private var bubbleDragStartY = 0f
    private var bubbleLayoutX = 0
    private var bubbleLayoutY = 100

    // ─── Window layout params helpers ─────────────────────────────────────────

    private fun bubbleLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        bubbleLayoutX, bubbleLayoutY,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private fun overlayCardLayoutParams(opacity: Float = 0.75f): WindowManager.LayoutParams {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.9f).toInt()
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            0, 80 * context.resources.displayMetrics.density.toInt(),  // 80dp bottom margin
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            alpha = opacity
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun create() {
        Timber.d("FloatingOverlayController.create() bắt đầu")
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        createBubble()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Timber.d("FloatingOverlayController.create() hoàn tất — lifecycle=${lifecycleRegistry.currentState}")
    }

    private fun createBubble() {
        Timber.d("createBubble() bắt đầu")
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayController)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayController)
            setContent {
                LiveLensTheme {
                    FloatingBubble(
                        onTap = { toggleExpanded() },
                        isExpanded = isExpanded
                    )
                }
            }
        }

        val lp = bubbleLayoutParams()
        setupDragBehavior(composeView, lp)

        try {
            windowManager.addView(composeView, lp)
            bubbleView = composeView
            Timber.d("Bubble view thêm vào WindowManager thành công ✓")
        } catch (e: Exception) {
            Timber.e(e, "Thêm bubble view vào WindowManager THẤT BẠI ✗")
        }
    }

    private fun createOverlayCard(opacity: Float) {
        Timber.d("createOverlayCard() opacity=$opacity, currentMode=$currentMode")
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayController)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayController)
            setContent {
                LiveLensTheme {
                    TranslationOverlayCard(
                        translationManager = translationManager,
                        currentMode = currentMode,
                        onModeChange = { mode ->
                            Timber.d("Overlay: mode thay đổi sang $mode")
                            currentMode = mode
                            translationManager.setMode(mode)
                        },
                        onMinimize = { collapse() }
                    )
                }
            }
        }

        try {
            windowManager.addView(composeView, overlayCardLayoutParams(opacity))
            overlayCardView = composeView
            Timber.d("Overlay card thêm vào WindowManager thành công ✓")
        } catch (e: Exception) {
            Timber.e(e, "Thêm overlay card vào WindowManager THẤT BẠI ✗")
        }
    }

    // ─── Drag behavior ────────────────────────────────────────────────────────

    private fun setupDragBehavior(view: View, lp: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleDragStartX = event.rawX - lp.x
                    bubbleDragStartY = event.rawY - lp.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (event.rawX - bubbleDragStartX).toInt()
                    lp.y = (event.rawY - bubbleDragStartY).toInt()
                    bubbleLayoutX = lp.x
                    bubbleLayoutY = lp.y
                    try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    // ─── State control ────────────────────────────────────────────────────────

    fun showAndSetMode(mode: TranslationMode) {
        Timber.i("showAndSetMode($mode) — isExpanded=$isExpanded, overlayCardView=${if (overlayCardView != null) "exists" else "null"}")
        currentMode = mode
        if (!isExpanded) expand()
        // Update the overlay card content
        overlayCardView?.let {
            Timber.d("showAndSetMode() — cập nhật nội dung overlay card")
            (it as? ComposeView)?.setContent {
                LiveLensTheme {
                    TranslationOverlayCard(
                        translationManager = translationManager,
                        currentMode = currentMode,
                        onModeChange = { m ->
                            Timber.d("Overlay: mode thay đổi sang $m")
                            currentMode = m
                            translationManager.setMode(m)
                        },
                        onMinimize = { collapse() }
                    )
                }
            }
        } ?: Timber.w("showAndSetMode() — overlayCardView null sau khi expand()")
    }

    private fun toggleExpanded() {
        Timber.d("toggleExpanded() — isExpanded=$isExpanded")
        if (isExpanded) collapse() else expand()
    }

    private fun expand(opacity: Float = 0.75f) {
        Timber.d("expand() — overlayCardView=${if (overlayCardView != null) "exists" else "null"}")
        isExpanded = true
        if (overlayCardView == null) {
            createOverlayCard(opacity)
        } else {
            overlayCardView?.visibility = View.VISIBLE
            Timber.d("expand() — overlay card visibility = VISIBLE")
        }
        refreshBubble()
    }

    fun collapse() {
        Timber.d("collapse() — isExpanded=$isExpanded")
        isExpanded = false
        overlayCardView?.visibility = View.GONE
        refreshBubble()
    }

    fun hide() {
        Timber.d("hide() — ẩn bubble và overlay card")
        collapse()
        bubbleView?.visibility = View.GONE
    }

    fun show() {
        Timber.d("show() — hiện bubble")
        bubbleView?.visibility = View.VISIBLE
    }

    private fun refreshBubble() {
        (bubbleView as? ComposeView)?.setContent {
            LiveLensTheme {
                FloatingBubble(
                    onTap = { toggleExpanded() },
                    isExpanded = isExpanded
                )
            }
        }
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        try { bubbleView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { overlayCardView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        bubbleView = null
        overlayCardView = null
        Timber.d("FloatingOverlayController destroyed")
    }
}
