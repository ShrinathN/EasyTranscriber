package com.easytranscriber

import android.app.Application
import android.os.Build
import com.easytranscriber.bluetooth.BluetoothHidManager
import com.easytranscriber.diagnostics.EventLog
import com.easytranscriber.dictation.DictationController
import com.easytranscriber.settings.SettingsRepository

class EasyTranscriberApp : Application() {
    lateinit var eventLog: EventLog
        private set
    lateinit var hidManager: BluetoothHidManager
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var controller: DictationController
        private set

    override fun onCreate() {
        super.onCreate()
        eventLog = EventLog()
        eventLog.add(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}"
        )
        hidManager = BluetoothHidManager(this, eventLog)
        settings = SettingsRepository(this)
        controller = DictationController(this, hidManager, settings, eventLog)
    }
}
