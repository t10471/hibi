package com.example.hibi

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.hibi.feature.meds.MedsScreen
import com.example.hibi.feature.since.SinceScreen
import com.example.hibi.feature.timer.TimerScreen
import com.example.hibi.notify.NAV_TAB_MEDS

private enum class Tab(val label: String, val icon: ImageVector, val route: String) {
    TIMER("タイマー", Icons.Filled.Timer, "timer"),
    MEDS("くすり", Icons.Filled.Medication, NAV_TAB_MEDS),
    SINCE("記録", Icons.Filled.History, "since"),
    ;

    companion object {
        fun fromRoute(route: String?): Tab? = entries.firstOrNull { it.route == route }
    }
}

/**
 * @param navTab route requested by a notification tap (e.g. [NAV_TAB_MEDS]), or null.
 * @param onNavHandled called once the request has been applied, so it is not re-applied on recomposition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HibiApp(
    navTab: String? = null,
    onNavHandled: () -> Unit = {},
) {
    var current by rememberSaveable { mutableStateOf(Tab.TIMER) }

    LaunchedEffect(navTab) {
        val target = Tab.fromRoute(navTab) ?: return@LaunchedEffect
        current = target
        onNavHandled()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(current.label) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (current) {
            Tab.TIMER -> TimerScreen(modifier = contentModifier)
            Tab.MEDS -> MedsScreen(modifier = contentModifier)
            Tab.SINCE -> SinceScreen(modifier = contentModifier)
        }
    }
}
