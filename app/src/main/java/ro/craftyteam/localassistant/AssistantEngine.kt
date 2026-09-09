package ro.craftyteam.localassistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AssistantEngine(private val context: Context) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    suspend fun initialize(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeInternal()

            val newEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
                )
            )

            newEngine.initialize()

            val newConversation = newEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are CodeAgent, a local Android phone assistant. The user may write Romanian or English. " +
                            "Use tools to actually perform requested actions. Never claim an action succeeded unless a tool returned success. " +
                            "For multi-step UI tasks, inspect the visible screen, tap visible text, type text and scroll as needed. " +
                            "Keep final replies short, natural and useful."
                    ),
                    tools = listOf(tool(PhoneTools(context))),
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.9,
                        temperature = 0.1
                    )
                )
            )

            engine = newEngine
            conversation = newConversation
        }
    }

    suspend fun execute(prompt: String): String = withContext(Dispatchers.IO) {
        val activeConversation = conversation ?: return@withContext "Agentul nu este încă pregătit."
        val response = activeConversation.sendMessage(prompt)
        response.toString().trim().ifBlank { "Gata." }
    }

    fun isReady(): Boolean = conversation != null

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }
}
