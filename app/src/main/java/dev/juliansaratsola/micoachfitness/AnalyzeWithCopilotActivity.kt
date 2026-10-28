package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Notification trampoline. The action is a share intent carrying the image URI
 * so the user can open it in Copilot (or any other image target).
 */
class AnalyzeWithCopilotActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null || !isShareableMediaUri(uri)) {
            finish()
            return
        }
        launchShare(uri)
        finish()
    }

    private fun launchShare(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: "image/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text))
            clipData = ClipData.newUri(contentResolver, "screenshot", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val targets = packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.no_share_target, Toast.LENGTH_LONG).show()
            return
        }
        for (info in targets) {
            grantUriPermission(
                info.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val chooser = Intent.createChooser(send, getString(R.string.action_analyze_copilot)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(chooser)
        } catch (error: Exception) {
            Log.w(TAG, "Share intent failed", error)
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun isShareableMediaUri(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "content") return false
        val authority = uri.authority ?: return false
        return authority.contains("media")
    }

    companion object {
        private const val TAG = "AnalyzeCopilot"
    }
}
