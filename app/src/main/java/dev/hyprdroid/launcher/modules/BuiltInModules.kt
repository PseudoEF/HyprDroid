package dev.hyprdroid.launcher.modules

/** The fixed set of internal modules shipped with HyprDroid; no dynamic code loading. */
internal object BuiltInModules {
    val registry: ModuleRegistry by lazy {
        ModuleRegistry(
            listOf(
                ClockModule,
                SystemMonitorModule,
                AppLauncherModule,
                CommandPanelModule,
            ),
        )
    }
}
