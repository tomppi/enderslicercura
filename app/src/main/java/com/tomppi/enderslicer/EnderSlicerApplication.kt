package com.tomppi.enderslicer

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.texturizer.BumpMeshActivity
import com.tomppi.enderslicer.window.ToolWindowOrientationPolicy
import java.lang.ref.WeakReference

/** Applies adaptive orientation policy without coupling either WebView host to it. */
class EnderSlicerApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumedToolActivity: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resumedToolActivity
            ?.get()
            ?.takeIf(::isAdaptiveToolActivity)
            ?.let(ToolWindowOrientationPolicy::apply)
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (isAdaptiveToolActivity(activity)) ToolWindowOrientationPolicy.apply(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (isAdaptiveToolActivity(activity)) {
            resumedToolActivity = WeakReference(activity)
            ToolWindowOrientationPolicy.apply(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedToolActivity?.get() === activity) resumedToolActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (resumedToolActivity?.get() === activity) resumedToolActivity = null
    }

    private fun isAdaptiveToolActivity(activity: Activity): Boolean =
        activity is SmartInfillActivity || activity is BumpMeshActivity
}
