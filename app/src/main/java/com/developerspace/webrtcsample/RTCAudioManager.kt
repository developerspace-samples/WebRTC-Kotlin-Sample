package com.developerspace.webrtcsample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import androidx.annotation.Nullable
import org.webrtc.ThreadUtils
import java.util.*
import kotlin.collections.HashSet


class RTCAudioManager(context: Context) {
    /**
     * AudioDevice is the names of possible audio devices that we currently
     * support.
     */
    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, NONE
    }

    /** AudioManager state.  */
    enum class AudioManagerState {
        UNINITIALIZED, PREINITIALIZED, RUNNING
    }

    /** Selected audio device change event.  */
    interface AudioManagerEvents {
        // Callback fired once audio device is changed or list of available audio devices changed.
        fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice?, availableAudioDevices: Set<AudioDevice?>?
        )
    }

    private val apprtcContext: Context

    @Nullable
    private val audioManager: AudioManager

    @Nullable
    private var audioManagerEvents: AudioManagerEvents? = null
    private var amState: AudioManagerState
    private var savedAudioMode = AudioManager.MODE_INVALID
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var hasWiredHeadset = false

    // Default audio device; speaker phone for video calls or earpiece for audio
    // only calls.
    private var defaultAudioDevice: AudioDevice? = null

    // Contains the currently selected audio device.
    // This device is changed automatically using a certain scheme where e.g.
    // a wired headset "wins" over speaker phone. It is also possible for a
    // user to explicitly select a device (and overrid any predefined scheme).
    // See |userSelectedAudioDevice| for details.
    private var selectedAudioDevice: AudioDevice? = null

    // Contains the user-selected audio device which overrides the predefined
    // selection scheme.
    private var userSelectedAudioDevice: AudioDevice? = null

    // Contains speakerphone setting: auto, true or false
    @Nullable
    private val useSpeakerphone: String?


    // Contains a list of available audio devices. A Set collection is used to
    // avoid duplicate elements.
    private var audioDevices: MutableSet<AudioDevice?> = HashSet()

    // Broadcast receiver for wired headset intent broadcasts.
    private val wiredHeadsetReceiver: BroadcastReceiver

    // Callback method for changes in audio focus.
    @Nullable
    private var audioFocusChangeListener: OnAudioFocusChangeListener? = null


    /* Receiver which handles changes in wired headset availability. */
    private inner class WiredHeadsetReceiver() : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val state = intent.getIntExtra("state", STATE_UNPLUGGED)
            val microphone = intent.getIntExtra("microphone", HAS_NO_MIC)
            val name = intent.getStringExtra("name")
            Log.d(TAG, "WiredHeadsetReceiver.onReceive"
                    + ": " + "a=" + intent.action.toString() + ", s=" +
                    (if (state == STATE_UNPLUGGED) "unplugged" else "plugged").toString()
                    + ", m=" + (if (microphone == HAS_MIC) "mic" else "no mic").toString()
                    + ", n=" + name.toString() + ", sb=" + isInitialStickyBroadcast)
            hasWiredHeadset = (state == STATE_PLUGGED)
            updateAudioDeviceState()
        }

        private val STATE_UNPLUGGED = 0
        private val STATE_PLUGGED = 1
        private val HAS_NO_MIC = 0
        private val HAS_MIC = 1
    }

    fun start(audioManagerEvents: AudioManagerEvents?) {
        Log.d(TAG, "start")
        ThreadUtils.checkIsOnMainThread()
        if (amState == AudioManagerState.RUNNING) {
            Log.e(TAG, "AudioManager is already active")
            return
        }
//        else if (amState == AudioManagerState.UNINITIALIZED) {
//            preInitAudio()
//        }
        // TODO perhaps call new method called preInitAudio() here if UNINITIALIZED.
        Log.d(TAG, "AudioManager starts...")
        this.audioManagerEvents = audioManagerEvents
        amState = AudioManagerState.RUNNING

        // Store current audio state so we can restore it when stop() is called.
        savedAudioMode = audioManager.mode
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute
        hasWiredHeadset = hasWiredHeadset()

        // Create an AudioManager.OnAudioFocusChangeListener instance.
        audioFocusChangeListener =
            OnAudioFocusChangeListener { focusChange ->

                // Called on the listener to notify if the audio focus for this listener has been changed.
                // The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
                // and whether that loss is transient, or whether the new focus holder will hold it for an
                // unknown amount of time.

                val typeOfChange: String
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> typeOfChange = "AUDIOFOCUS_GAIN"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> typeOfChange =
                        "AUDIOFOCUS_GAIN_TRANSIENT"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> typeOfChange =
                        "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> typeOfChange =
                        "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
                    AudioManager.AUDIOFOCUS_LOSS -> typeOfChange = "AUDIOFOCUS_LOSS"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> typeOfChange =
                        "AUDIOFOCUS_LOSS_TRANSIENT"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> typeOfChange =
                        "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                    else -> typeOfChange = "AUDIOFOCUS_INVALID"
                }
                Log.d(TAG, "onAudioFocusChange: $typeOfChange")
            }

        // Request audio playout focus (without ducking) and install listener for changes in focus.
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus request granted for VOICE_CALL streams")
        } else {
            Log.e(TAG, "Audio focus request failed")
        }

        // Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
        // required to be in this mode when playout and/or recording starts for
        // best possible VoIP performance.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Always disable microphone mute during a WebRTC call.
        setMicrophoneMute(false)

        // Set initial device states.
        userSelectedAudioDevice = AudioDevice.NONE
        selectedAudioDevice = AudioDevice.NONE
        audioDevices.clear()

        // Do initial selection of audio device. This setting can later be changed
        // either by adding/removing a BT or wired headset or by covering/uncovering
        // the proximity sensor.
        updateAudioDeviceState()

        // Register receiver for broadcast intents related to adding/removing a
        // wired headset.
        registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        Log.d(TAG, "AudioManager started")
    }

    fun stop() {
        Log.d(TAG, "stop")
        ThreadUtils.checkIsOnMainThread()
        if (amState != AudioManagerState.RUNNING) {
            Log.e(
                TAG,
                "Trying to stop AudioManager in incorrect state: $amState"
            )
            return
        }
        amState = AudioManagerState.UNINITIALIZED
        unregisterReceiver(wiredHeadsetReceiver)

        // Restore previously stored audio states.
        setSpeakerphoneOn(savedIsSpeakerPhoneOn)
        setMicrophoneMute(savedIsMicrophoneMute)
        audioManager.mode = savedAudioMode

        // Abandon audio focus. Gives the previous focus owner, if any, focus.
        audioManager.abandonAudioFocus(audioFocusChangeListener)
        audioFocusChangeListener = null
        Log.d(TAG, "Abandoned audio focus for VOICE_CALL streams")

        audioManagerEvents = null
        Log.d(TAG, "AudioManager stopped")
    }

    /** Changes selection of the currently active audio device.  */
    private fun setAudioDeviceInternal(device: AudioDevice?) {
        Log.d(TAG, "setAudioDeviceInternal(device=$device)")
        if (audioDevices.contains(device)) {
            when (device) {
                AudioDevice.SPEAKER_PHONE -> setSpeakerphoneOn(true)
                AudioDevice.EARPIECE -> setSpeakerphoneOn(false)
                AudioDevice.WIRED_HEADSET -> setSpeakerphoneOn(false)
                else -> Log.e(TAG, "Invalid audio device selection")
            }
        }
        selectedAudioDevice = device
    }

    /**
     * Changes default audio device.
     */
    fun setDefaultAudioDevice(defaultDevice: AudioDevice?) {
        ThreadUtils.checkIsOnMainThread()
        when (defaultDevice) {
            AudioDevice.SPEAKER_PHONE -> defaultAudioDevice = defaultDevice
            AudioDevice.EARPIECE -> if (hasEarpiece()) {
                defaultAudioDevice = defaultDevice
            } else {
                defaultAudioDevice = AudioDevice.SPEAKER_PHONE
            }
            else -> Log.e(TAG, "Invalid default audio device selection")
        }
        Log.d(TAG, "setDefaultAudioDevice(device=$defaultAudioDevice)")
        updateAudioDeviceState()
    }

    /** Changes selection of the currently active audio device.  */
    fun selectAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        if (!audioDevices.contains(device)) {
            Log.e(
                TAG,
                "Can not select $device from available $audioDevices"
            )
        }
        userSelectedAudioDevice = device
        updateAudioDeviceState()
    }

    /** Returns current set of available/selectable audio devices.  */
    fun getAudioDevices(): Set<AudioDevice> {
        ThreadUtils.checkIsOnMainThread()
        return Collections.unmodifiableSet(HashSet(audioDevices)) as Set<AudioDevice>
    }

    /** Returns the currently selected audio device.  */
    fun getSelectedAudioDevice(): AudioDevice? {
        ThreadUtils.checkIsOnMainThread()
        return selectedAudioDevice
    }

    /** Helper method for receiver registration.  */
    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        apprtcContext.registerReceiver(receiver, filter)
    }

    /** Helper method for unregistration of an existing receiver.  */
    private fun unregisterReceiver(receiver: BroadcastReceiver) {
        apprtcContext.unregisterReceiver(receiver)
    }

    /** Sets the speaker phone mode.  */
    private fun setSpeakerphoneOn(on: Boolean) {
        val wasOn = audioManager.isSpeakerphoneOn
        if (wasOn == on) {
            return
        }
        audioManager.isSpeakerphoneOn = on
    }

    /** Sets the microphone mute state.  */
    private fun setMicrophoneMute(on: Boolean) {
        val wasMuted = audioManager.isMicrophoneMute
        if (wasMuted == on) {
            return
        }
        audioManager.isMicrophoneMute = on
    }

    /** Gets the current earpiece state.  */
    private fun hasEarpiece(): Boolean {
        return apprtcContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    /**
     * Checks whether a wired headset is connected or not.
     * This is not a valid indication that audio playback is actually over
     * the wired headset as audio routing depends on other conditions. We
     * only use it as an early indicator (during initialization) of an attached
     * wired headset.
     */
    @Deprecated("")
    private fun hasWiredHeadset(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return audioManager.isWiredHeadsetOn
        } else {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device: AudioDeviceInfo in devices) {
                val type = device.type
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    Log.d(TAG, "hasWiredHeadset: found wired headset")
                    return true
                } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    Log.d(TAG, "hasWiredHeadset: found USB audio device")
                    return true
                }
            }
            return false
        }
    }

    /**
     * Updates list of possible audio devices and make new device selection.
     */
    fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(
            TAG, ("--- updateAudioDeviceState: "
                    + "wired headset=" + hasWiredHeadset)
        )
        Log.d(
            TAG, ("Device status: "
                    + "available=" + audioDevices + ", "
                    + "selected=" + selectedAudioDevice + ", "
                    + "user selected=" + userSelectedAudioDevice)
        )


        // Update the set of available audio devices.
        val newAudioDevices: MutableSet<AudioDevice?> = HashSet()

        if (hasWiredHeadset) {
            // If a wired headset is connected, then it is the only possible option.
            newAudioDevices.add(AudioDevice.WIRED_HEADSET)
        } else {
            // No wired headset, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE)
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE)
            }
        }
        // Store state which is set to true if the device list has changed.
        var audioDeviceSetUpdated = audioDevices != newAudioDevices
        // Update the existing audio device set.
        audioDevices = newAudioDevices
        // Correct user selected audio devices if needed.
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            // If user selected speaker phone, but then plugged wired headset then make
            // wired headset as user selected device.
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET
        }
        if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            // If user selected wired headset, but then unplugged wired headset then make
            // speaker phone as user selected device.
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE
        }


        // Update selected audio device.
        val newAudioDevice: AudioDevice?
        if (hasWiredHeadset) {
            // If a wired headset is connected, but Bluetooth is not, then wired headset is used as
            // audio device.
            newAudioDevice = AudioDevice.WIRED_HEADSET
        } else {
            // No wired headset and no Bluetooth, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            // |defaultAudioDevice| contains either AudioDevice.SPEAKER_PHONE or AudioDevice.EARPIECE
            // depending on the user's selection.
            newAudioDevice = defaultAudioDevice
        }
        // Switch to new device but only if there has been any changes.
        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            // Do the required device switch.
            setAudioDeviceInternal(newAudioDevice)
            Log.d(
                TAG, ("New device status: "
                        + "available=" + audioDevices + ", "
                        + "selected=" + newAudioDevice)
            )
            if (audioManagerEvents != null) {
                // Notify a listening client that audio device has been changed.
                audioManagerEvents!!.onAudioDeviceChanged(selectedAudioDevice, audioDevices)
            }
        }
        Log.d(TAG, "--- updateAudioDeviceState done")
    }

    companion object {
        private val TAG = "AppRTCAudioManager"
        private val SPEAKERPHONE_AUTO = "auto"
        private val SPEAKERPHONE_TRUE = "true"
        private val SPEAKERPHONE_FALSE = "false"

        /** Construction.  */
        fun create(context: Context): RTCAudioManager {
            return RTCAudioManager(context)
        }
    }

    init {
        Log.d(TAG, "ctor")
        ThreadUtils.checkIsOnMainThread()
        apprtcContext = context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        wiredHeadsetReceiver = WiredHeadsetReceiver()
        amState = AudioManagerState.UNINITIALIZED
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        useSpeakerphone = sharedPreferences.getString(
            context.getString(R.string.pref_speakerphone_key),
            context.getString(R.string.pref_speakerphone_default)
        )
        Log.d(TAG, "useSpeakerphone: $useSpeakerphone")
        if ((useSpeakerphone == SPEAKERPHONE_FALSE)) {
            defaultAudioDevice = AudioDevice.EARPIECE
        } else {
            defaultAudioDevice = AudioDevice.SPEAKER_PHONE
        }
        Log.d(TAG, "defaultAudioDevice: $defaultAudioDevice")
    }
}