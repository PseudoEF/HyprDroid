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
            ) + listOfNotNull(ExternalHelloTileModule.takeIf { BuildConfig.EXTERNAL_LUA }) +
                if (BuildConfig.V2_FIXTURE_LUA || BuildConfig.V2_OFFICIAL_LUA) listOf(
                    ExternalV2TileModule("clock-tile", "Clock Tile"),
                    ExternalV2TileModule("battery-tile", "Battery Tile"),
                ) else emptyList(),
        )
    }
}
