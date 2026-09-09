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
    data class AgentDecision(val action: String, val argument: String = "")

    private var engine: Engine? = null

    suspend fun initialize(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeInternal()
            engine = initializeEngine(modelFile)
        }
    }

    suspend fun nextAction(goal: String, screen: String, step: Int): AgentDecision = withContext(Dispatchers.IO) {
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

    private fun parseDecision(raw: String): AgentDecision {
        val cleaned = raw
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace("```", "")
            .trim()

        val match = DECISION_REGEX.find(cleaned)
            ?: return AgentDecision("FAIL", "Nu am putut decide următorul pas.")

        val action = match.groupValues[1].uppercase()
        val argument = match.groupValues.getOrElse(2) { "" }
            .trim()
            .trim('"', '\'', ' ', ')', ']', '}')

        return AgentDecision(action, argument)
    }

    private fun closeInternal() {
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        private val DECISION_REGEX = Regex(
            "(?i)(OPEN_APP|TAP|TYPE|SCROLL_DOWN|SCROLL_UP|BACK|HOME|DONE|FAIL)(?:\\s*\\|\\s*([^\\n\\r]*))?"
        )

        private const val PLANNER_INSTRUCTION = """
You are the action planner inside CodeAgent, a local Android phone agent.
You never chat and never describe what you would do. You choose exactly one next action.
The user can write Romanian or English.

Allowed outputs, exactly one line:
OPEN_APP|visible app name
TAP|visible text or accessibility label
TYPE|text to type
SCROLL_DOWN
SCROLL_UP
BACK
HOME
DONE|short confirmation in the user's language
FAIL|short reason in the user's language

Rules:
- Perform multi-step goals one action at a time. After each action you will receive a fresh accessibility snapshot.
- At the first step, if the goal names an app and that app is not already visible, use OPEN_APP.
- Never claim success until the visible UI indicates the requested destination or result was reached.
- Prefer TAP on visible text or accessibility labels from the snapshot. Do not invent labels that are not visible.
- If the user says "profilul meu" or "my profile", navigate using visible menu/profile/account controls and infer the user's own profile from the current UI; do not ask for their name if the UI exposes an account/profile entry.
- Use TYPE only when an editable field is visible and the goal requires text entry.
- Use scrolling only when the likely control is not visible yet.
- Do not output explanations, markdown, JSON, reasoning, or more than one action.
"""
    }
}
