package com.livelens.translator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import com.livelens.translator.model.GemmaTranslateManager

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var gemmaTranslateManager: GemmaTranslateManager

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Create notification channels
        createNotificationChannels()

        // Initialize Gemma on background thread to avoid cold-start delay
        // The manager handles lazy init gracefully if models aren't yet downloaded
        gemmaTranslateManager.initializeAsync()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LiveLens translation service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannels(listOf(serviceChannel))
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "livelens_service"
        const val NOTIFICATION_ID_SERVICE = 1001
    }
}
