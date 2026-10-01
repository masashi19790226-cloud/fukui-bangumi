package jp.personal.fukuiepg.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

enum class AppTab(val label: String, val icon: ImageVector) {
    NOW("今放送中", Icons.Filled.LiveTv),
    GRID("番組表", Icons.Filled.CalendarViewWeek),
    SEARCH("検索", Icons.Filled.Search),
    FAV("通知", Icons.Filled.Notifications),
}

class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { AppRoot() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFFFFB74D))
    } else {
        lightColorScheme(primary = Color(0xFF1565C0), secondary = Color(0xFFEF6C00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(AppTab.NOW) }
    val snackbar = remember { SnackbarHostState() }
    val msg by vm.message.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val selected by vm.selected.collectAsState()

    LaunchedEffect(msg) {
        msg?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    } else {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "更新") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                AppTab.NOW -> NowScreen(vm)
                AppTab.GRID -> GridScreen(vm)
                AppTab.SEARCH -> SearchScreen(vm)
                AppTab.FAV -> FavoritesScreen(vm)
            }
        }
    }

    selected?.let { DetailSheet(vm, it) }
}
