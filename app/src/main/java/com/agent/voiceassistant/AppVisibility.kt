package com.agent.voiceassistant

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** UI visibility; a foreground service does not count as visible UI. */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    private val started = mutableSetOf<Activity>()
    @Volatile var isVisible = false
        private set

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        isVisible = true
    }
    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        isVisible = started.isNotEmpty() || activity.isChangingConfigurations
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
