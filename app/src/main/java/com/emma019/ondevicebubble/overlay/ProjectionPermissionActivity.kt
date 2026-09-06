package com.emma019.ondevicebubble.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Tiny trampoline that requests MediaProjection consent, then hands the result
 * to [BubbleOverlayService].
 */
class ProjectionPermissionActivity : AppCompatActivity() {
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, BubbleOverlayService::class.java).apply {
                action = BubbleOverlayService.ACTION_START
                putExtra(BubbleOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(BubbleOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(service)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ProjectionPermissionActivity::class.java)
    }
}
