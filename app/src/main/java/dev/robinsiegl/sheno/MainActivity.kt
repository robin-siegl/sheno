package dev.robinsiegl.sheno

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val destination = MutableStateFlow<String?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination.value = "${intent.data?.lastPathSegment ?: "today"}|${System.nanoTime()}"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) destination.value = intent.data?.lastPathSegment
        setContent {
            val vm: ShenoViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ShenoViewModel(sheno) as T
            })
            val model by vm.model.collectAsStateWithLifecycle()
            var permissionTick by remember { mutableIntStateOf(0) }
            val lifecycle = LocalLifecycleOwner.current
            val route by destination.collectAsStateWithLifecycle()
            LaunchedEffect(route) {
                route?.let { vm.accept(AppIntent.Navigate(if (it.substringBefore('|') == "medications") Page.MEDICATIONS else Page.TODAY)) }
            }
            LaunchedEffect(lifecycle) {
                lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) { delay(60_000); vm.accept(AppIntent.Refresh) }
                }
            }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) { permissionTick++; vm.accept(AppIntent.Refresh) }
                }
                lifecycle.lifecycle.addObserver(observer)
                onDispose { lifecycle.lifecycle.removeObserver(observer) }
            }
            BackHandler(model.editing || model.selectedId != null || model.page == Page.SETTINGS) {
                when {
                    model.editing -> vm.accept(AppIntent.CloseEditor)
                    model.selectedId != null -> vm.accept(AppIntent.Open(null))
                    else -> vm.accept(AppIntent.Navigate(Page.TODAY))
                }
            }
            ShenoTheme(model.preferences) { ShenoScreen(model, vm, permissionTick) }
        }
    }
}
