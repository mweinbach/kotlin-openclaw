package ai.openclaw.runtime.devices

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A minimal LifecycleOwner for CameraX usage outside of an Activity/Fragment.
 * Immediately enters RESUMED state so the camera provider can bind use cases.
 */
internal object NoOpLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
}
