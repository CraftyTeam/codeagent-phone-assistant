package ro.craftyteam.localassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var assistantEngine: AssistantEngine
    private lateinit var directRouter: DirectCommandRouter
    private lateinit var statusText: TextView
    private lateinit var outputText: TextView
    private lateinit var promptInput: EditText
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        assistantEngine = AssistantEngine(applicationContext)
        directRouter = DirectCommandRouter(PhoneTools(applicationContext))

        statusText = findViewById(R.id.statusText)
        outputText = findViewById(R.id.outputText)
        promptInput = findViewById(R.id.promptInput)
        sendButton = findViewById(R.id.sendButton)

        findViewById<Button>(R.id.importModelButton).setOnClickListener { chooseModel() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        sendButton.setOnClickListener { runPrompt() }
        promptInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                runPrompt()
                true
            } else {
                false
            }
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 200)
        }

        val savedPath = getPreferences(MODE_PRIVATE).getString("model_path", null)
        if (!savedPath.isNullOrBlank()) {
            val model = File(savedPath)
            if (model.isFile) {
                loadModel(model)
            }
        }
    }

    override fun onDestroy() {
        assistantEngine.close()
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API but kept intentionally for min-dependency model import.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            data?.data?.let { importModel(it) }
        }
    }

    private fun chooseModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 100)
    }

    private fun importModel(uri: Uri) {
        setBusy(true, "Copiez modelul local…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val modelDir = File(filesDir, "models").apply { mkdirs() }
                    val destination = File(modelDir, "mobile-actions.litertlm")
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Nu pot deschide fișierul selectat." }
                        destination.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                    }
                    destination
                }
            }

            result.onSuccess { model ->
                getPreferences(MODE_PRIVATE).edit().putString("model_path", model.absolutePath).apply()
                loadModel(model)
            }.onFailure {
                setBusy(false, "Import eșuat: ${it.message}")
            }
        }
    }

    private fun loadModel(model: File) {
        setBusy(true, "Încarc modelul local…")
        scope.launch {
            val result = assistantEngine.initialize(model)
            result.onSuccess {
                setBusy(false, "Model local activ • ${model.length() / 1024 / 1024} MB")
            }.onFailure {
                setBusy(false, "Model invalid/incompatibil: ${it.message}")
            }
        }
    }

    private fun runPrompt() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isBlank()) return

        promptInput.setText("")
        appendOutput("\n\nTu: $prompt")

        val direct = directRouter.execute(prompt)
        if (direct != null) {
            appendOutput("\nAssistant: $direct")
            return
        }

        if (!assistantEngine.isReady()) {
            appendOutput("\nAssistant: Importă mai întâi modelul .litertlm.")
            return
        }

        setBusy(true, "Execut comanda local…")
        scope.launch {
            runCatching { assistantEngine.execute(prompt) }
                .onSuccess {
                    appendOutput("\nAssistant: $it")
                    setBusy(false, "Model local activ")
                }
                .onFailure {
                    appendOutput("\nAssistant: Eroare: ${it.message}")
                    setBusy(false, "Model local activ")
                }
        }
    }

    private fun appendOutput(text: String) {
        outputText.append(text)
    }

    private fun setBusy(busy: Boolean, status: String) {
        sendButton.isEnabled = !busy
        statusText.text = status
    }
}
