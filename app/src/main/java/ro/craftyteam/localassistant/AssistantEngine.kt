package ro.craftyteam.localassistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AssistantEngine(private val context: Context) {
    data class IntentDecision(val mode: String, val content: String = "")
    data class AgentDecision(val action: String, val argument: String = "")

    private var engine: Engine? = null

    suspend fun initialize(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeInternal()
            engine = initializeEngine(modelFile)
        }
    }

    suspend fun interpret(
        userInput: String,
        history: String,
        pendingGoal: String,
        installedApps: String,
        screen: String
    ): IntentDecision = withContext(Dispatchers.IO) {
        val activeEngine = engine ?: return@withContext IntentDecision("CLARIFY", "Agentul nu este încă pregătit.")

        activeEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(ORCHESTRATOR_INSTRUCTION),
                samplerConfig = SamplerConfig(
                    topK = 1,
                    topP = 1.0,
                    temperature = 0.0
                )
            )
        ).use { conversation ->
            val prompt = buildString {
                append("CONVERSATION:\n")
                append(history)
                append("\n\nPENDING_FAILED_OR_INCOMPLETE_GOAL:\n")
                append(pendingGoal.ifBlank { "none" })
                append("\n\nINSTALLED_APPS:\n")
                append(installedApps)
                append("\n\nCURRENT_SCREEN:\n")
                append(screen.ifBlank { "No useful screen context." })
                append("\n\nNEW_USER_MESSAGE:\n")
                append(userInput.trim())
                append("\n\nReturn exactly one MODE line.")
            }

            val response = conversation.sendMessage(
                prompt,
                extraContext = mapOf("enable_thinking" to false)
            )
            parseIntent(response.toString())
        }
    }

    suspend fun nextAction(
        goal: String,
        screen: String,
        step: Int,
        installedApps: String
    ): AgentDecision = withContext(Dispatchers.IO) {
        val activeEngine = engine ?: return@withContext AgentDecision("FAIL", "Agentul nu este încă pregătit.")

        activeEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(PLANNER_INSTRUCTION),
                samplerConfig = SamplerConfig(
                    topK = 1,
                    topP = 1.0,
                    temperature = 0.0
                )
            )
        ).use { conversation ->
            val prompt = buildString {
                append("GOAL:\n")
                append(goal.trim())
                append("\n\nSTEP: ")
                append(step)
                append("\n\nINSTALLED_APPS:\n")
                append(installedApps)
                append("\n\nVISIBLE_ANDROID_UI:\n")
                append(screen.ifBlank { "No readable UI elements." })
                append("\n\nReturn exactly one allowed action line.")
            }

            val response = conversation.sendMessage(
                prompt,
                extraContext = mapOf("enable_thinking" to false)
            )
            parseDecision(response.toString())
        }
    }

    fun isReady(): Boolean = engine != null

    fun close() {
        closeInternal()
    }

    private fun initializeEngine(modelFile: File): Engine {
        val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        var gpuEngine: Engine? = null

        runCatching {
            gpuEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    cacheDir = cacheDir
                )
            )
            gpuEngine!!.initialize()
            return gpuEngine!!
        }.onFailure {
            runCatching { gpuEngine?.close() }
        }

        return Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = cacheDir
            )
        ).also { it.initialize() }
    }

    private fun parseIntent(raw: String): IntentDecision {
        val cleaned = cleanResponse(raw)
        val match = INTENT_REGEX.find(cleaned)
            ?: return IntentDecision("CLARIFY", "Nu am înțeles suficient de clar. Ce vrei să fac?")

        val mode = match.groupValues[1].uppercase()
        val content = match.groupValues.getOrElse(2) { "" }
            .trim()
            .trim('"', '\'', ' ', ')', ']', '}')
            .take(600)

        return IntentDecision(mode, content)
    }

    private fun parseDecision(raw: String): AgentDecision {
        val cleaned = cleanResponse(raw)
        val match = DECISION_REGEX.find(cleaned)
            ?: return AgentDecision("FAIL", "Nu am putut decide următorul pas.")

        val action = match.groupValues[1].uppercase()
        val argument = match.groupValues.getOrElse(2) { "" }
            .trim()
            .trim('"', '\'', ' ', ')', ']', '}')
            .take(500)

        return AgentDecision(action, argument)
    }

    private fun cleanResponse(raw: String): String {
        return raw
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace("```", "")
            .trim()
    }

    private fun closeInternal() {
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        private val INTENT_REGEX = Regex(
            "(?i)(CHAT|ACTION|CLARIFY|CANCEL)\\s*\\|\\s*([^\\n\\r]*)"
        )

        private val DECISION_REGEX = Regex(
            "(?i)(OPEN_APP|TAP|TYPE|SCROLL_DOWN|SCROLL_UP|BACK|HOME|RECENTS|NOTIFICATIONS|FLASHLIGHT_ON|FLASHLIGHT_OFF|SET_VOLUME|OPEN_WIFI|OPEN_BLUETOOTH|MAP|DIAL|DONE|FAIL)(?:\\s*\\|\\s*([^\\n\\r]*))?"
        )

        private const val ORCHESTRATOR_INSTRUCTION = """
You are the intent and conversation orchestrator inside CodeAgent, a fully local Android phone assistant.
Your job is to understand what the user means before any phone action happens.
The user may speak naturally in Romanian or English, use abbreviations, pronouns, corrections, follow-ups, slang, incomplete phrases, or refer to previous turns.

Return exactly one line in one of these forms:
CHAT|short natural reply
ACTION|normalized operational goal
CLARIFY|one short clarification question
CANCEL|short confirmation

Interpretation rules:
- CHAT is for greetings, conversation, capability questions, explanations and messages that do not ask the phone to perform an operation.
- ACTION is for a concrete operation on the device or inside an app.
- CLARIFY only when a required entity cannot be inferred from conversation, installed apps or the visible screen.
- CANCEL is for stop/cancel/never mind requests.
- Preserve conversational context. Resolve pronouns and short follow-ups from recent turns.
- If there is a pending failed/incomplete goal and the new message is a correction such as an app name, recipient, destination or value, merge that correction into the pending goal instead of treating it as a new unrelated request.
- For app abbreviations or informal names, use the INSTALLED_APPS list to infer the most likely exact visible app name. Example: if Facebook is installed and the user says "fb", normalize the goal using "Facebook".
- Do not output a sequence of low-level taps. ACTION must describe the user's intended end result, not implementation details.
- Do not invent private user data, contacts, credentials or facts that are not present.
- If the user asks what you can do, explain succinctly that CodeAgent can open and navigate apps, tap visible controls, type, scroll, control basic phone settings, open maps/dialer/notifications, and perform multi-step tasks using the visible Android UI.
- A greeting such as "salut" is CHAT, never an ACTION.
- Keep all replies concise and in the user's language.
- Never output JSON, markdown, reasoning or multiple lines.
"""

        private const val PLANNER_INSTRUCTION = """
You are the operational action planner inside CodeAgent, a local Android phone agent.
The intent layer has already converted the user's request into a normalized operational goal.
You never chat and never reinterpret the request as a new conversation. You choose exactly one next device action.

Allowed outputs, exactly one line:
OPEN_APP|exact installed app name
TAP|visible text or accessibility label
TYPE|text to type
SCROLL_DOWN
SCROLL_UP
BACK
HOME
RECENTS
NOTIFICATIONS
FLASHLIGHT_ON
FLASHLIGHT_OFF
SET_VOLUME|integer 0-100
OPEN_WIFI
OPEN_BLUETOOTH
MAP|place or address
DIAL|phone number
DONE|short confirmation in the user's language
FAIL|short reason in the user's language

Operational rules:
- Execute multi-step goals one action at a time. After each action you receive a fresh accessibility snapshot and the previous action result.
- Use INSTALLED_APPS as grounding. OPEN_APP must use an exact visible app name from that list; never invent an app name.
- At the first step, if the goal requires an app and that app is not already the current foreground app, OPEN_APP it.
- After opening an app, inspect the fresh UI and continue toward the requested end state.
- Prefer TAP only on text or accessibility labels actually present in the visible UI snapshot.
- Do not declare DONE merely because an app opened if the user requested a destination or operation inside it.
- If the goal says "profilul meu" / "my profile", use visible account/profile/menu controls to reach the signed-in user's own profile. Do not ask for their name if the app exposes a profile/account entry.
- TYPE only when an editable field is visible and text entry is required.
- Scroll only when needed to reveal a likely target.
- Use direct device actions such as volume, flashlight, notifications, Wi-Fi or Bluetooth when they match the goal.
- Never claim success unless the requested end state is visible or a direct device action returned success.
- Never output explanations, markdown, JSON, reasoning or more than one action.
"""
    }
}
