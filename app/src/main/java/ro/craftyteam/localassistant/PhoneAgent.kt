package ro.craftyteam.localassistant

import kotlinx.coroutines.delay

class PhoneAgent(
    private val assistantEngine: AssistantEngine,
    private val tools: PhoneTools
) {
    suspend fun execute(goal: String): String {
        var lastFeedback = "none"
        var consecutiveFailures = 0

        for (step in 1..MAX_STEPS) {
            val service = PhoneAccessibilityService.instance
                ?: return "Controlul telefonului nu este activ."

            val snapshot = service.snapshot(MAX_SCREEN_ITEMS)
            val plannerScreen = buildString {
                append(snapshot)
                append("\n\nLAST_ACTION_RESULT: ")
                append(lastFeedback)
            }

            val decision = assistantEngine.nextAction(goal, plannerScreen, step)

            when (decision.action) {
                "DONE" -> return decision.argument.ifBlank { "Gata." }
                "FAIL" -> return decision.argument.ifBlank { "Nu am putut termina acțiunea." }
            }

            val result = executeDecision(decision)
            val success = result["result"] == "success"
            lastFeedback = formatFeedback(decision, result)

            if (success) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    return result["message"] ?: "Nu am putut continua acțiunea pe ecranul curent."
                }
            }

            delay(waitAfter(decision.action))
        }

        return "Nu am reușit să termin acțiunea în suficienți pași."
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
        private const val MAX_STEPS = 10
        private const val MAX_SCREEN_ITEMS = 60
    }
}
