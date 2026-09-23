package ru.pauza.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class PauseDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, PauseDeviceAdminReceiver::class.java)
    }
}
