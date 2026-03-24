package com.livelens.translator.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import timber.log.Timber

/**
 * Helper for requesting and managing MediaProjection for AudioPlaybackCapture.
 */
object MediaProjectionHelper {

    /**
     * Create the intent for starting a screen capture request.
     * The result should be passed to AudioCaptureService as EXTRA_MEDIA_PROJECTION_DATA.
     */
    fun createScreenCaptureIntent(context: Context): Intent {
        val mediaProjectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mediaProjectionManager.createScreenCaptureIntent()
    }
}
