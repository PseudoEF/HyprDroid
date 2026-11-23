package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.BuildConfig

/** The fixed set of internal modules shipped with HyprDroid; no dynamic code loading. */
internal object BuiltInModules {
    val registry: ModuleRegistry by lazy {
        ModuleRegistry(
            listOf(
                ClockModule,
                SystemMonitorModule,
                AppLauncherModule,
                CommandPanelModule,
                DroidFetchModule,
                DroidFetchTileModule,
            ) + listOfNotNull(ExternalHelloTileModule.takeIf { BuildConfig.EXTERNAL_LUA }),
        )
    }
}
