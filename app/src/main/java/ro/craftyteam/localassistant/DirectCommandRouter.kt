package ro.craftyteam.localassistant

import java.text.Normalizer

class DirectCommandRouter(private val tools: PhoneTools) {
    fun execute(command: String): String? {
        val normalized = normalize(command)

        if (isMultiStep(normalized)) {
            return null
        }

        if (normalized.contains("aprinde lanterna") || normalized.contains("porneste lanterna")) {
            return result(tools.turnOnFlashlight())
        }

        if (normalized.contains("stinge lanterna") || normalized.contains("opreste lanterna")) {
            return result(tools.turnOffFlashlight())
        }

        if ((normalized.contains("wifi") || normalized.contains("wi-fi")) && normalized.contains("set")) {
            return result(tools.openWifiSettings())
        }

        if (normalized.contains("bluetooth") && normalized.contains("set")) {
            return result(tools.openBluetoothSettings())
        }

        Regex("volum(?:ul)?(?: la)?\\s*(\\d{1,3})%?").find(normalized)?.let {
            return result(tools.setVolumePercent(it.groupValues[1].toInt()))
        }

        if (normalized == "inapoi" || normalized == "back") {
            return result(tools.goBack())
        }

        if (normalized == "home" || normalized.contains("ecranul principal")) {
            return result(tools.goHome())
        }

        if (normalized.contains("notificari") && (normalized.contains("deschide") || normalized.contains("arata"))) {
            return result(tools.openNotifications())
        }

        if (normalized.contains("scroll") && (normalized.contains("jos") || normalized.contains("down"))) {
            return result(tools.scrollDown())
        }

        if (normalized.contains("scroll") && (normalized.contains("sus") || normalized.contains("up"))) {
            return result(tools.scrollUp())
        }

        val openMatch = Regex("^(?:deschide|porneste)\\s+(.+)$").find(normalized)
        if (openMatch != null) {
            val app = openMatch.groupValues[1].trim()
            if (app.isNotBlank()) {
                return result(tools.openApp(app))
            }
        }

        return null
    }

    private fun isMultiStep(command: String): Boolean {
        if (command.contains(',') || command.contains(';')) return true
        return Regex("\\b(si|apoi|dupa aceea|dupa care|and|then)\\b").containsMatchIn(command)
    }

    private fun result(map: Map<String, String>): String {
        return if (map["result"] == "success") "Executat." else map["message"] ?: "Acțiunea nu a putut fi executată."
    }

    private fun normalize(input: String): String {
        return Normalizer.normalize(input.lowercase().trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
    }
}
