package dev.robinsiegl.sheno

import android.os.Bundle
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
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ShenoViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ShenoViewModel(sheno) as T
            })
            val model by vm.model.collectAsStateWithLifecycle()
            var permissionTick by remember { mutableIntStateOf(0) }
            val lifecycle = LocalLifecycleOwner.current
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
