package dev.hyprdroid.launcher.overlay

import androidx.lifecycle.*
import androidx.savedstate.*

/** Compose overlays have their own lifecycle and ViewModel store, independent of MainActivity. */
internal class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.CREATED }
    fun visible(value: Boolean) { registry.currentState = if (value) Lifecycle.State.RESUMED else Lifecycle.State.CREATED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}
