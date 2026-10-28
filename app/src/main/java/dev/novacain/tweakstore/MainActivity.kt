package dev.novacain.tweakstore

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-wide state: sources, installer, root status. Survives activity recreation. */
object Store {
    lateinit var repos: Repos
    lateinit var installer: Installer
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** null — not checked yet. */
    val root = MutableStateFlow<Boolean?>(null)

    fun init(ctx: Context) {
        if (::repos.isInitialized) return
        val app = ctx.applicationContext
        repos = Repos(app)
        installer = Installer(app)
        scope.launch {
            repos.load(network = false)
            installer.refresh(repos.tweaks().map { it.id })
            launch(Dispatchers.IO) { root.value = Root.available() }
            refresh()
        }
    }

    suspend fun refresh() {
        repos.load(network = true)
        installer.refresh(repos.tweaks().map { it.id })
    }
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        val deepLink = intent?.data?.toString()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive()) {
                StoreApp(addRepo = deepLink)
            }
        }
    }

    /** Back from another app (e.g. after uninstalling a tweak elsewhere) — recheck what's installed. */
    override fun onResume() {
        super.onResume()
        Store.installer.refresh(Store.repos.tweaks().map { it.id })
        // root may have been granted in Magisk meanwhile (its prompt can be missed when the screen is off)
        if (Store.root.value == false) Store.scope.launch(Dispatchers.IO) { Store.root.value = Root.available() }
    }
}
