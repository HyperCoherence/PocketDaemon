package com.pocketdaemon.pocket_daemon

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object AudioRouteHelper {

    fun preferredExternalOutput(audioManager: AudioManager): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isExternalOutput(it) }
            .minByOrNull { outputRank(it) }
    }

    fun preferredMonitorOutput(audioManager: AudioManager): AudioDeviceInfo? {
        return preferredExternalOutput(audioManager) ?: builtInSpeaker(audioManager)
    }

    fun preferredCommunicationInput(audioManager: AudioManager, output: AudioDeviceInfo?): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return when {
            output == null -> null
            isBluetoothCommunication(output) -> inputs.firstOrNull { isBluetoothCommunication(it) }
            isWired(output) -> inputs.firstOrNull { isWired(it) }
            output.type == AudioDeviceInfo.TYPE_USB_HEADSET -> inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            else -> null
        }
    }

    fun communicationOutput(audioManager: AudioManager): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isCommunicationOutput(it) }
            .minByOrNull { communicationRank(it) }
    }

    fun builtInSpeaker(audioManager: AudioManager): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    fun isBluetoothCommunication(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                device.type == AudioDeviceInfo.TYPE_HEARING_AID)
    }

    fun isBluetoothMedia(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    fun isWired(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }

    fun label(device: AudioDeviceInfo?): String {
        if (device == null) return "default"
        val product = device.productName?.toString()?.ifBlank { null }
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                "ble_headset"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                "ble_speaker"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                device.type == AudioDeviceInfo.TYPE_HEARING_AID) {
                "hearing_aid"
            } else {
                "type_${device.type}"
            }
        }
        return if (product == null) "$type#${device.id}" else "$type#$product"
    }

    private fun isExternalOutput(device: AudioDeviceInfo): Boolean {
        return isBluetoothCommunication(device) ||
            isBluetoothMedia(device) ||
            isWired(device) ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun isCommunicationOutput(device: AudioDeviceInfo): Boolean {
        return isBluetoothCommunication(device) ||
            isWired(device) ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun outputRank(device: AudioDeviceInfo): Int {
        return when {
            isBluetoothCommunication(device) -> 0
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> 1
            isWired(device) -> 2
            isBluetoothMedia(device) -> 3
            else -> 100
        }
    }

    private fun communicationRank(device: AudioDeviceInfo): Int {
        return when {
            isBluetoothCommunication(device) -> 0
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> 1
            isWired(device) -> 2
            else -> 100
        }
    }
}
