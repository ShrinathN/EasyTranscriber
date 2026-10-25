package com.easytranscriber.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.easytranscriber.EasyTranscriberApp
import com.easytranscriber.bluetooth.HostDevice

/** Thin access point from the UI to the process-wide controller, keyboard manager and log. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyTranscriberApp
    val controller = app.controller
    val hid = app.hidManager
    val log = app.eventLog

    val settings = controller.settings
    val session = controller.session
    val engine = controller.engine
    val models = controller.models
    val delivery = controller.delivery.state
    val deliveredChars = controller.delivery.deliveredChars
    val lastSession = controller.lastSession
    val testInput = controller.testInput
    val keyboard = hid.state
    val downloads = controller.downloader.states

    fun pairedDevices(): List<HostDevice> = hid.bondedDevices()
}
