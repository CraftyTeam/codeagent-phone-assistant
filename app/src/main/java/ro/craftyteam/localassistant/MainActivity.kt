package ro.craftyteam.localassistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var assistantEngine: AssistantEngine
    private lateinit var phoneAgent: PhoneAgent
    private lateinit var orchestrator: ConversationOrchestrator
    private lateinit var bootstrapper: ModelBootstrapper

    private lateinit var setupScreen: LinearLayout
    private lateinit var setupTitle: TextView
    private lateinit var setupBody: TextView
    private lateinit var setupDetail: TextView
    private lateinit var setupProgress: ProgressBar
    private lateinit var setupPrimary: TextView
    private lateinit var chatScreen: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var promptInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var agentStatus: TextView

    private var bootstrapJob: Job? = null
    private var modelReady = false
    private var pendingPrompt: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val phoneTools = PhoneTools(applicationContext)
        val appCatalog = AppCatalog(applicationContext)
        assistantEngine = AssistantEngine(applicationContext)
        phoneAgent = PhoneAgent(assistantEngine, phoneTools, appCatalog)
        orchestrator = ConversationOrchestrator(assistantEngine, phoneAgent, appCatalog)
        bootstrapper = ModelBootstrapper(applicationContext)

        setupScreen = findViewById(R.id.setupScreen)
        setupTitle = findViewById(R.id.setupTitle)
        setupBody = findViewById(R.id.setupBody)
        setupDetail = findViewById(R.id.setupDetail)
        setupProgress = findViewById(R.id.setupProgress)
        setupPrimary = findViewById(R.id.setupPrimary)
        chatScreen = findViewById(R.id.chatScreen)
        messagesScroll = findViewById(R.id.messagesScroll)
        messagesContainer = findViewById(R.id.messagesContainer)
        promptInput = findViewById(R.id.promptInput)
        sendButton = findViewById(R.id.sendButton)
        agentStatus = findViewById(R.id.agentStatus)

        sendButton.setOnClickListener { runPrompt() }
        promptInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                runPrompt()
                true
            } else {
                false
            }
        }

        startBootstrap()
    }

    override fun onResume() {
        super.onResume()
        if (modelReady) continueAfterPreparation()
    }

    override fun onDestroy() {
        bootstrapJob?.cancel()
        assistantEngine.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return

        val prompt = pendingPrompt
        pendingPrompt = null
        if (prompt.isNullOrBlank()) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            promptInput.setText("")
            executePrompt(prompt)
        } else {
            addMessage("Pentru lanternă, Android are nevoie de permisiunea pentru cameră.", false)
        }
    }

    private fun startBootstrap() {
        bootstrapJob?.cancel()
        showPreparing()

        bootstrapJob = scope.launch {
            var retryDelay = 2_000L

            while (isActive && !modelReady) {
                val result = bootstrapper.prepare { downloaded, total ->
                    runOnUiThread { renderDownloadProgress(downloaded, total) }
                }

                val model = result.getOrNull()
                if (model != null) {
                    setupProgress.progress = 100
                    setupDetail.text = "Pornesc agentul…"

                    val initialization = assistantEngine.initialize(model)
                    if (initialization.isSuccess) {
                        modelReady = true
                        continueAfterPreparation()
                        return@launch
                    }

                    showFatalError("Nu am putut porni agentul pe acest telefon.")
                    return@launch
                }

                val error = result.exceptionOrNull()
                val preparationError = error as? ModelBootstrapper.PreparationException
                val message = preparationError?.message ?: "Conexiunea s-a întrerupt. Reiau automat."

                if (preparationError?.retryable == false) {
                    showFatalError(message)
                    return@launch
                }

                setupDetail.text = message
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun showPreparing() {
        chatScreen.visibility = View.GONE
        setupScreen.visibility = View.VISIBLE
        setupProgress.visibility = View.VISIBLE
        setupPrimary.visibility = View.GONE
        setupTitle.text = "Îți pregătesc CodeAgent"
        setupBody.text = "Prima configurare se face o singură dată. După aceea, agentul rulează direct pe telefon."
        setupDetail.text = "Pregătesc fișierele necesare…"
        setupProgress.progress = 0
    }

    private fun renderDownloadProgress(downloaded: Long, total: Long) {
        if (modelReady || total <= 0L) return
        val percent = ((downloaded.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        val downloadedMb = downloaded / 1024L / 1024L
        val totalMb = total / 1024L / 1024L
        setupProgress.progress = percent
        setupDetail.text = "Pregătesc agentul • $percent% • $downloadedMb / $totalMb MB"
    }

    private fun showFatalError(message: String) {
        setupProgress.visibility = View.GONE
        setupTitle.text = "Nu am putut termina configurarea"
        setupBody.text = message
        setupDetail.text = ""
        setupPrimary.apply {
            visibility = View.VISIBLE
            text = "Încearcă din nou"
            setOnClickListener { startBootstrap() }
        }
    }

    private fun continueAfterPreparation() {
        if (isPhoneControlEnabled()) {
            showChat()
        } else {
            showPhoneControlStep()
        }
    }

    private fun showPhoneControlStep() {
        chatScreen.visibility = View.GONE
        setupScreen.visibility = View.VISIBLE
        setupProgress.visibility = View.GONE
        setupTitle.text = "Ultimul pas"
        setupBody.text = "Pentru a putea apăsa, scrie și naviga în alte aplicații, Android trebuie să permită CodeAgent să controleze ecranul."
        setupDetail.text = "În ecranul următor, selectează CodeAgent și activează permisiunea."
        setupPrimary.apply {
            visibility = View.VISIBLE
            text = "Continuă"
            setOnClickListener { openPhoneControlSettings() }
        }
    }

    private fun showChat() {
        setupScreen.visibility = View.GONE
        chatScreen.visibility = View.VISIBLE
        agentStatus.text = "●  Gata să te ajut"
        if (messagesContainer.childCount == 0) {
            addMessage("Salut. Spune-mi ce vrei să fac pe telefon.", false)
        }
        promptInput.requestFocus()
    }

    private fun openPhoneControlSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun isPhoneControlEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(this, PhoneAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val service = info.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == expected
        }
    }

    private fun runPrompt() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isBlank() || !modelReady) return

        if (requiresCameraPermission(prompt) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPrompt = prompt
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }

        promptInput.setText("")
        executePrompt(prompt)
    }

    private fun executePrompt(prompt: String) {
        addMessage(prompt, true)
        setWorking(true)

        scope.launch {
            runCatching { orchestrator.handle(prompt) }
                .onSuccess { addMessage(it, false) }
                .onFailure { addMessage("Nu am reușit să procesez cererea. Încearcă din nou.", false) }
            setWorking(false)
        }
    }

    private fun setWorking(working: Boolean) {
        sendButton.isEnabled = !working
        sendButton.alpha = if (working) 0.45f else 1f
        agentStatus.text = if (working) "●  Lucrez…" else "●  Gata să te ajut"
    }

    private fun addMessage(message: String, fromUser: Boolean) {
        val bubble = TextView(this).apply {
            text = message.trim()
            textSize = 16f
            setTextColor(if (fromUser) Color.WHITE else Color.rgb(24, 24, 24))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(if (fromUser) Color.rgb(18, 18, 18) else Color.rgb(244, 244, 244))
            }
        }

        val row = LinearLayout(this).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            addView(
                bubble,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        messagesContainer.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun requiresCameraPermission(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return normalized.contains("lantern") || normalized.contains("flashlight") || normalized.contains("torch")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 200
    }
}
