package dev.juliansaratsola.micoachfitness

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object PermissionHelper {
    fun missingRuntimePermissions(context: Context): Array<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                missing += Manifest.permission.POST_NOTIFICATIONS
            }
            if (!granted(context, Manifest.permission.READ_MEDIA_IMAGES)) {
                missing += Manifest.permission.READ_MEDIA_IMAGES
            }
        } else if (!granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            missing += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return missing.toTypedArray()
    }

    fun hasPhotoAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    private fun granted(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
