package ro.craftyteam.localassistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.time.LocalDateTime
import java.time.ZoneId

class PhoneTools(private val context: Context) : ToolSet {
    private val appCatalog = AppCatalog(context)

    @Tool(description = "Turns the phone flashlight on.")
    fun turnOnFlashlight(): Map<String, String> = setFlashlight(true)

    @Tool(description = "Turns the phone flashlight off.")
    fun turnOffFlashlight(): Map<String, String> = setFlashlight(false)

    @Tool(description = "Opens the Android WiFi settings screen.")
    fun openWifiSettings(): Map<String, String> = launch(Intent(Settings.ACTION_WIFI_SETTINGS))

    @Tool(description = "Opens the Android Bluetooth settings screen.")
    fun openBluetoothSettings(): Map<String, String> = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    @Tool(description = "Opens the main Android system settings screen.")
    fun openSystemSettings(): Map<String, String> = launch(Intent(Settings.ACTION_SETTINGS))

    @Tool(description = "Opens an installed app by its visible launcher name or a close abbreviation.")
    fun openApp(
        @ToolParam(description = "Requested application name.") appName: String
    ): Map<String, String> {
        val resolved = appCatalog.resolve(appName)
            ?: return mapOf("result" to "error", "message" to "Nu am găsit aplicația: $appName")

        val intent = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            ?: return mapOf("result" to "error", "message" to "Nu pot deschide ${resolved.label}")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return mapOf(
            "result" to "success",
            "app" to resolved.label,
            "package" to resolved.packageName
        )
    }

    @Tool(description = "Shows a location, address, business or place on the map.")
    fun showLocationOnMap(
        @ToolParam(description = "Location, address, business or place name.") location: String
    ): Map<String, String> {
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(location)}")
        return launch(Intent(Intent.ACTION_VIEW, uri))
    }

    @Tool(description = "Opens the dialer with a phone number. It does not place the call without the user's final tap.")
    fun dialNumber(
        @ToolParam(description = "Phone number to dial.") phoneNumber: String
    ): Map<String, String> = launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}")))

    @Tool(description = "Opens the SMS app with recipient and message filled in. The user can review before sending.")
    fun composeSms(
        @ToolParam(description = "Recipient phone number.") phoneNumber: String,
        @ToolParam(description = "SMS message text.") message: String
    ): Map<String, String> {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phoneNumber)}"))
        intent.putExtra("sms_body", message)
        return launch(intent)
    }

    @Tool(description = "Opens the email app with a new email prepared.")
    fun sendEmail(
        @ToolParam(description = "Recipient email address.") to: String,
        @ToolParam(description = "Email subject.") subject: String,
        @ToolParam(description = "Email body.") body: String
    ): Map<String, String> {
        val uri = Uri.parse("mailto:${Uri.encode(to)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        return launch(Intent(Intent.ACTION_SENDTO, uri))
    }

    @Tool(description = "Opens the Contacts app with a new contact prepared for creation.")
    fun createContact(
        @ToolParam(description = "Contact first name.") firstName: String,
        @ToolParam(description = "Contact last name.") lastName: String,
        @ToolParam(description = "Contact phone number.") phoneNumber: String,
        @ToolParam(description = "Contact email address.") email: String
    ): Map<String, String> {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName".trim())
            putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
            putExtra(ContactsContract.Intents.Insert.EMAIL, email)
        }
        return launch(intent)
    }

    @Tool(description = "Creates a calendar event by opening the calendar event editor.")
    fun createCalendarEvent(
        @ToolParam(description = "Event date and time in ISO format YYYY-MM-DDTHH:MM:SS.") datetime: String,
        @ToolParam(description = "Event title.") title: String
    ): Map<String, String> {
        val startMillis = runCatching {
            LocalDateTime.parse(datetime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrElse {
            return mapOf("result" to "error", "message" to "Invalid datetime. Use YYYY-MM-DDTHH:MM:SS")
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.Events.TITLE, title)
        }
        return launch(intent)
    }

    @Tool(description = "Sets media volume to a percentage between 0 and 100.")
    fun setVolumePercent(
        @ToolParam(description = "Volume percentage from 0 to 100.") percent: Int
    ): Map<String, String> {
        val safePercent = percent.coerceIn(0, 100)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (max * safePercent / 100.0).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI)
        return mapOf("result" to "success", "volume_percent" to safePercent.toString())
    }

    @Tool(description = "Reads a compact description of the currently visible Android user interface. Use before deciding what to tap in another app.")
    fun inspectScreen(): Map<String, String> {
        val service = PhoneAccessibilityService.instance
            ?: return mapOf("result" to "error", "message" to "Accessibility service is not enabled.")
        return mapOf("result" to "success", "screen" to service.snapshot())
    }

    @Tool(description = "Taps a visible UI element whose text or accessibility label matches the requested text.")
    fun tapVisibleText(
        @ToolParam(description = "Visible text or accessibility label to tap.") text: String
    ): Map<String, String> {
        val service = PhoneAccessibilityService.instance
            ?: return mapOf("result" to "error", "message" to "Accessibility service is not enabled.")
        val success = service.tapText(text)
        return mapOf("result" to if (success) "success" else "error")
    }

    @Tool(description = "Types text into the focused or first visible editable field on the current screen.")
    fun typeText(
        @ToolParam(description = "Text to type into the current editable field.") text: String
    ): Map<String, String> {
        val service = PhoneAccessibilityService.instance
            ?: return mapOf("result" to "error", "message" to "Accessibility service is not enabled.")
        val success = service.typeText(text)
        return mapOf("result" to if (success) "success" else "error", "screen" to service.snapshot())
    }

    @Tool(description = "Scrolls the current screen downward.")
    fun scrollDown(): Map<String, String> = scroll(true)

    @Tool(description = "Scrolls the current screen upward.")
    fun scrollUp(): Map<String, String> = scroll(false)

    @Tool(description = "Performs the Android Back global action.")
    fun goBack(): Map<String, String> = globalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    @Tool(description = "Performs the Android Home global action.")
    fun goHome(): Map<String, String> = globalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    @Tool(description = "Opens Android recent apps.")
    fun openRecentApps(): Map<String, String> = globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    @Tool(description = "Opens the Android notification shade.")
    fun openNotifications(): Map<String, String> = globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    private fun scroll(forward: Boolean): Map<String, String> {
        val service = PhoneAccessibilityService.instance
            ?: return mapOf("result" to "error", "message" to "Accessibility service is not enabled.")
        val success = if (forward) service.scrollForward() else service.scrollBackward()
        return mapOf("result" to if (success) "success" else "error")
    }

    private fun globalAction(action: Int): Map<String, String> {
        val service = PhoneAccessibilityService.instance
            ?: return mapOf("result" to "error", "message" to "Accessibility service is not enabled.")
        val success = service.performGlobalAction(action)
        return mapOf("result" to if (success) "success" else "error")
    }

    private fun launch(intent: Intent): Map<String, String> {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            mapOf("result" to "success")
        }.getOrElse {
            mapOf("result" to "error", "message" to (it.message ?: "Unable to open requested action."))
        }
    }

    private fun setFlashlight(enabled: Boolean): Map<String, String> {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return mapOf("result" to "error", "message" to "Camera permission is required for flashlight control.")
        }

        return runCatching {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: error("No flashlight camera found.")

            cameraManager.setTorchMode(cameraId, enabled)
            mapOf("result" to "success", "flashlight" to if (enabled) "on" else "off")
        }.getOrElse {
            mapOf("result" to "error", "message" to (it.message ?: "Flashlight action failed."))
        }
    }
}
