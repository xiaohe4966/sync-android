package com.squadsync.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol between master and slaves. Each subtype carries a literal `type`
 * discriminator. `classDiscriminator = "type"` in the JSON config lets us decode
 * polymorphic messages by their `type` field.
 */
@Serializable
sealed class Wire {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val roomCode: String,
        val deviceName: String,
        val role: String,                 // "master" | "slave"
        val maxVolume: Int = 15
    ) : Wire()

    @Serializable
    @SerialName("state")
    data class State(
        val deviceName: String,
        val playing: Boolean,
        val volume: Int,
        val maxVolume: Int,
        // 0..100 percent of the device's screen brightness.
        val brightness: Int = 0
    ) : Wire()

    @Serializable
    @SerialName("apps")
    data class AppsList(
        val apps: List<AppEntry>
    ) : Wire()

    @Serializable
    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean = false
    )

    @Serializable
    @SerialName("cmd")
    data class Cmd(
        val action: String,
        val value: Int? = null,
        // Free-form string argument used by commands like `launch_app` to pass
        // the target package name. Kept null-defaulted so existing commands
        // don't have to set it.
        val target: String? = null,
        val sender: String = "master"
    ) : Wire()

    @Serializable
    @SerialName("ack")
    data class Ack(
        val action: String,
        val ok: Boolean,
        val message: String? = null
    ) : Wire()

    @Serializable
    @SerialName("ping")
    data class Ping(
        val ts: Long = System.currentTimeMillis()
    ) : Wire()

    @Serializable
    @SerialName("pong")
    data class Pong(
        val ts: Long = System.currentTimeMillis()
    ) : Wire()

    @Serializable
    @SerialName("error")
    data class ErrorMsg(
        val message: String
    ) : Wire()
}

object Actions {
    const val VOLUME = "volume"
    const val VOLUME_PERCENT = "volume_percent"
    const val VOLUME_UP = "volume_up"
    const val VOLUME_DOWN = "volume_down"
    const val BRIGHTNESS_PERCENT = "brightness_percent"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val TOGGLE = "toggle"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val MUTE = "mute"
    const val UNMUTE = "unmute"
    const val LAUNCH_APP = "launch_app"
    const val LIST_APPS = "list_apps"
}