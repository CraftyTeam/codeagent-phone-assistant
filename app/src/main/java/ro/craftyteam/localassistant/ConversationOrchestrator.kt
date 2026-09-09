package ro.craftyteam.localassistant

class ConversationOrchestrator(
    private val assistantEngine: AssistantEngine,
    private val phoneAgent: PhoneAgent,
    private val appCatalog: AppCatalog
) {
    private data class Turn(val role: String, val text: String)

    private val history = ArrayDeque<Turn>()
    private var pendingGoal: String? = null

    suspend fun handle(userInput: String): String {
        val screen = PhoneAccessibilityService.instance?.snapshot(24).orEmpty()
        val decision = assistantEngine.interpret(
            userInput = userInput,
            history = historyText(),
            pendingGoal = pendingGoal.orEmpty(),
            installedApps = appCatalog.summary(),
            screen = screen
        )

        return when (decision.mode) {
            "CHAT" -> finishTurn(userInput, decision.content.ifBlank { "Spune-mi ce vrei să fac." })
            "CLARIFY" -> finishTurn(userInput, decision.content.ifBlank { "Ce anume vrei să fac?" })
            "CANCEL" -> {
                pendingGoal = null
                finishTurn(userInput, decision.content.ifBlank { "Am oprit acțiunea." })
            }
            "ACTION" -> {
                val goal = decision.content.ifBlank { userInput.trim() }
                pendingGoal = goal
                val result = phoneAgent.execute(goal)
                if (result.success) pendingGoal = null
                finishTurn(userInput, result.message)
            }
            else -> finishTurn(userInput, "Nu am înțeles suficient de clar. Reformulează comanda.")
        }
    }

    fun remember(userInput: String, assistantReply: String) {
        addTurn("USER", userInput)
        addTurn("ASSISTANT", assistantReply)
    }

    private fun finishTurn(userInput: String, reply: String): String {
        remember(userInput, reply)
        return reply
    }

    private fun historyText(): String {
        if (history.isEmpty()) return "No previous conversation."
        return history.joinToString("\n") { "${it.role}: ${it.text.take(260)}" }.takeLast(2600)
    }

    private fun addTurn(role: String, text: String) {
        history.addLast(Turn(role, text.trim()))
        while (history.size > MAX_TURNS) history.removeFirst()
    }

    companion object {
        private const val MAX_TURNS = 10
    }
}
