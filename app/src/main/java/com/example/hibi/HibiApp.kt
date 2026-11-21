package com.example.hibi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hibi.data.AuthManager
import com.example.hibi.feature.meds.MedsScreen
import com.example.hibi.feature.since.SinceScreen
import com.example.hibi.feature.stock.StockScreen
import com.example.hibi.feature.timer.TimerScreen
import com.example.hibi.feature.workout.WorkoutScreen
import com.example.hibi.notify.NAV_TAB_MEDS
import com.example.hibi.notify.NAV_TAB_STOCK
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector, val route: String) {
    TIMER("タイマー", Icons.Filled.Timer, "timer"),
    WORKOUT("筋トレ", Icons.Filled.FitnessCenter, "workout"),
    MEDS("くすり", Icons.Filled.Medication, NAV_TAB_MEDS),
    SINCE("記録", Icons.Filled.History, "since"),
    STOCK("かぶ", Icons.Filled.TrendingUp, NAV_TAB_STOCK),
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
    val currentUser by AuthManager.currentUser.collectAsStateWithLifecycle()
    val isOwner = currentUser?.email == AuthManager.ALLOWED_EMAIL
    var showAccountDialog by remember { mutableStateOf(false) }

    LaunchedEffect(navTab) {
        val target = Tab.fromRoute(navTab) ?: return@LaunchedEffect
        current = target
        onNavHandled()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(current.label) },
                actions = {
                    if (isOwner) {
                        IconButton(onClick = { showAccountDialog = true }) {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = "アカウント情報",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        },
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
            Tab.WORKOUT -> if (isOwner) WorkoutScreen(modifier = contentModifier) else OwnerSignInView(modifier = contentModifier)
            Tab.MEDS -> if (isOwner) MedsScreen(modifier = contentModifier) else OwnerSignInView(modifier = contentModifier)
            Tab.SINCE -> if (isOwner) SinceScreen(modifier = contentModifier) else OwnerSignInView(modifier = contentModifier)
            Tab.STOCK -> if (isOwner) StockScreen(modifier = contentModifier) else OwnerSignInView(modifier = contentModifier)
        }
    }

    if (showAccountDialog && currentUser != null) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("アカウント設定") },
            text = {
                Text("ログイン中: ${currentUser?.email.orEmpty()}\nFirestore へのアクセスが暗号化・オーナー認証されています。")
            },
            confirmButton = {
                TextButton(onClick = { showAccountDialog = false }) {
                    Text("閉じる")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        AuthManager.signOut()
                        showAccountDialog = false
                    }
                ) {
                    Text("ログアウト", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun OwnerSignInView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Google ログインが必要です",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "データを第三者から保護するため、クラウド同期（筋トレ・くすり・記録・かぶ）のご利用には管理者アカウント (${AuthManager.ALLOWED_EMAIL}) でのログインが必要です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = {
                        isSigningIn = true
                        errorMessage = null
                        scope.launch {
                            val res = AuthManager.signInWithGoogle(context)
                            res.onFailure { e ->
                                errorMessage = e.message ?: "ログインに失敗しました"
                            }
                            isSigningIn = false
                        }
                    },
                    enabled = !isSigningIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("認証中...")
                    } else {
                        Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Google アカウントでログイン")
                    }
                }
            }
        }
    }
}
