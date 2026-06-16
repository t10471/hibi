package com.example.hibi

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.hibi.notify.EXTRA_NAV_TAB
import com.example.hibi.notify.ensureMedChannel
import com.example.hibi.ui.theme.HibiTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    /** Tab requested by a notification tap; consumed once by [HibiApp]. */
    private var navTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureMedChannel(this)
        maybeRequestNotificationPermission()
        // 初回生成時のみ通知 extra を消費する。回転などの再生成では再読込しない
        // （= ユーザーが手動で開いたタブを上書きして「くすり」へ引き戻さない）。
        if (savedInstanceState == null) {
            navTab = consumeNavTab(intent)
        }
        setContent {
            HibiTheme {
                HibiApp(
                    navTab = navTab,
                    onNavHandled = { navTab = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navTab = consumeNavTab(intent)
    }

    /**
     * Reads the requested tab from a notification intent and clears it, so a later
     * relaunch from recents (which reuses this intent) won't re-trigger navigation.
     */
    private fun consumeNavTab(intent: Intent?): String? {
        val tab = intent?.getStringExtra(EXTRA_NAV_TAB) ?: return null
        intent.removeExtra(EXTRA_NAV_TAB)
        return tab
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
