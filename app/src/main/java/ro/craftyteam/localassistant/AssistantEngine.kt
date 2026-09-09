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
                        "You are a local Android phone assistant. The user may write Romanian or English. " +
                            "Use tools to actually perform requested actions. Never claim an action succeeded unless a tool returned success. " +
                            "For multi-step UI tasks, inspect the visible screen, tap visible text, type text and scroll as needed. " +
                            "Keep replies very short."
                    ),
                    tools = listOf(tool(PhoneTools(context))),
                    samplerConfig = SamplerConfig(
                        topK = 64,
                        topP = 0.95,
                        temperature = 0.0
                    )
                )
            )

            engine = newEngine
            conversation = newConversation
        }
    }

    suspend fun execute(prompt: String): String = withContext(Dispatchers.IO) {
        val activeConversation = conversation ?: return@withContext "Modelul nu este încărcat."
        val response = activeConversation.sendMessage(prompt)
        response.toString().ifBlank { "Acțiunea a fost procesată." }
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
