package ro.craftyteam.localassistant

import kotlinx.coroutines.delay

class PhoneAgent(
    private val assistantEngine: AssistantEngine,
    private val tools: PhoneTools,
    private val appCatalog: AppCatalog
) {
    data class ExecutionResult(val success: Boolean, val message: String)

    suspend fun execute(goal: String): ExecutionResult {
        var lastFeedback = "none"
        var consecutiveFailures = 0
        val installedApps = appCatalog.summary()

        for (step in 1..MAX_STEPS) {
            val service = PhoneAccessibilityService.instance
                ?: return ExecutionResult(false, "Controlul telefonului nu este activ.")

            val snapshot = service.snapshot(MAX_SCREEN_ITEMS)
            val plannerScreen = buildString {
                append(snapshot)
                append("\n\nLAST_ACTION_RESULT: ")
                append(lastFeedback)
            }

            val decision = assistantEngine.nextAction(
                goal = goal,
                screen = plannerScreen,
                step = step,
                installedApps = installedApps
            )

            when (decision.action) {
                "DONE" -> return ExecutionResult(true, decision.argument.ifBlank { "Gata." })
                "FAIL" -> return ExecutionResult(false, decision.argument.ifBlank { "Nu am putut termina acțiunea." })
            }

            val result = executeDecision(decision)
            val success = result["result"] == "success"
            lastFeedback = formatFeedback(decision, result)

            if (success && isTerminalDirectAction(decision.action)) {
                return ExecutionResult(true, result["message"] ?: "Gata.")
            }

            if (success) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    return ExecutionResult(
                        false,
                        result["message"] ?: "Nu am putut continua acțiunea pe ecranul curent."
                    )
                }
            }

            delay(waitAfter(decision.action))
        }

        return ExecutionResult(false, "Nu am reușit să termin acțiunea în suficienți pași.")
    }

    private fun executeDecision(decision: AssistantEngine.AgentDecision): Map<String, String> {
        return when (decision.action) {
            "OPEN_APP" -> if (decision.argument.isBlank()) invalidArgument() else tools.openApp(decision.argument)
            "TAP" -> if (decision.argument.isBlank()) invalidArgument() else tools.tapVisibleText(decision.argument)
            "TYPE" -> if (decision.argument.isBlank()) invalidArgument() else tools.typeText(decision.argument)
            "SCROLL_DOWN" -> tools.scrollDown()
            "SCROLL_UP" -> tools.scrollUp()
            "BACK" -> tools.goBack()
            "HOME" -> tools.goHome()
            "RECENTS" -> tools.openRecentApps()
            "NOTIFICATIONS" -> tools.openNotifications()
            "FLASHLIGHT_ON" -> tools.turnOnFlashlight()
            "FLASHLIGHT_OFF" -> tools.turnOffFlashlight()
            "SET_VOLUME" -> decision.argument.toIntOrNull()?.let { tools.setVolumePercent(it) } ?: invalidArgument()
            "OPEN_WIFI" -> tools.openWifiSettings()
            "OPEN_BLUETOOTH" -> tools.openBluetoothSettings()
            "MAP" -> if (decision.argument.isBlank()) invalidArgument() else tools.showLocationOnMap(decision.argument)
            "DIAL" -> if (decision.argument.isBlank()) invalidArgument() else tools.dialNumber(decision.argument)
            else -> mapOf("result" to "error", "message" to "Acțiune necunoscută: ${decision.action}")
        }
    }

    private fun formatFeedback(
        decision: AssistantEngine.AgentDecision,
        result: Map<String, String>
    ): String {
        val status = result["result"] ?: "unknown"
        val message = result["message"]?.take(180).orEmpty()
        return buildString {
            append(decision.action)
            if (decision.argument.isNotBlank()) {
                append("|")
                append(decision.argument.take(120))
            }
            append(" -> ")
            append(status)
            if (message.isNotBlank()) {
                append(": ")
                append(message)
            }
        }
    }

    private fun isTerminalDirectAction(action: String): Boolean {
        return action in setOf(
            "FLASHLIGHT_ON",
            "FLASHLIGHT_OFF",
            "SET_VOLUME",
            "OPEN_WIFI",
            "OPEN_BLUETOOTH",
            "MAP",
            "DIAL",
            "NOTIFICATIONS",
            "RECENTS",
            "HOME"
        )
    }

    private fun invalidArgument(): Map<String, String> {
        return mapOf("result" to "error", "message" to "Lipsește argumentul acțiunii.")
    }

    private fun waitAfter(action: String): Long {
        return when (action) {
            "OPEN_APP" -> 1_600L
            "TAP" -> 900L
            "SCROLL_DOWN", "SCROLL_UP" -> 750L
            "BACK", "HOME" -> 700L
            else -> 450L
        }
    }

    companion object {
        private const val MAX_STEPS = 12
        private const val MAX_SCREEN_ITEMS = 70
    }
}
