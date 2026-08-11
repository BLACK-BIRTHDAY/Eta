package fuck.andes.agent.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import fuck.andes.R
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.overlay.AgentOverlayVisibilityPolicy
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.ui.app.AgentAppTheme
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EtaVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner {
    init {
        setTheme(R.style.Theme_EtaVoiceInteractionSession)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeClient = AgentRuntimeClient(context, AndroidAgentLogger)
    private val conversationKey = "eta_voice_${UUID.randomUUID()}"
    private var conversationHistory = emptyList<AgentModelClient.ConversationMessage>()
    private var runJob: Job? = null
    private var activeRunId: String? = null
    private var hiddenForForegroundOperation = false
    private var inputText by mutableStateOf("")
    private var inputFocusRequestKey by mutableStateOf(-1)
    private var uiState by mutableStateOf(
        EtaVoiceUiState(
            phase = EtaVoicePhase.READY,
            status = "输入请求",
        ),
    )

    override fun onCreate() {
        super.onCreate()
        configureSessionWindow()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        setUiEnabled(true)
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@EtaVoiceInteractionSession)
        setViewTreeSavedStateRegistryOwner(this@EtaVoiceInteractionSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AgentAppTheme {
                EtaVoicePanel(
                    state = uiState,
                    input = inputText,
                    inputFocusRequestKey = inputFocusRequestKey,
                    onInputChange = { inputText = it },
                    onSubmit = ::submitInput,
                    onStop = ::stopCurrentRun,
                    onClose = ::closeSession,
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        moveLifecycleToShown()
        hiddenForForegroundOperation = false
        if (activeRunId == null) inputFocusRequestKey++
    }

    override fun onHide() {
        super.onHide()
        moveLifecycleToHidden()
        if (hiddenForForegroundOperation) {
            hiddenForForegroundOperation = false
            return
        }
        cancelCurrentRun()
    }

    override fun onBackPressed() {
        if (activeRunId != null) {
            hiddenForForegroundOperation = true
            hide()
        } else {
            closeSession()
        }
    }

    override fun onCloseSystemDialogs() {
        closeSession()
    }

    override fun onDestroy() {
        cancelCurrentRun()
        scope.cancel()
        moveLifecycleToHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun moveLifecycleToShown() {
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private fun moveLifecycleToHidden() {
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    private fun configureSessionWindow() {
        window.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }

    private fun submitInput() {
        val prompt = inputText.trim()
        if (prompt.isEmpty() || activeRunId != null) return
        inputText = ""
        submitPrompt(prompt)
    }

    private fun submitPrompt(prompt: String) {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || activeRunId != null) return
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        uiState = EtaVoiceUiState(
            phase = EtaVoicePhase.PROCESSING,
            query = normalized,
            status = "Eta 正在思考",
        )
        runJob = scope.launch {
            val config = AgentModelClient.loadConfig()
            val payload = AgentExternalArchivePayload(
                userText = normalized,
                conversationKey = conversationKey,
                title = normalized.take(40),
            )
            val result = runtimeClient.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = normalized,
                    config = config,
                    images = emptyList(),
                    history = conversationHistory,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = "$conversationKey:$runId",
                        source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        payload = payload.toJson(),
                        dismissEntrySurfaceOnForegroundOperation = true,
                    ),
                ),
                onEvent = { event -> handleRuntimeEvent(runId, event) },
            )
            val shouldAcknowledge = withContext(Dispatchers.Main.immediate) {
                if (activeRunId != runId) return@withContext false
                activeRunId = null
                runJob = null
                if (result.ok) {
                    conversationHistory = conversationHistory +
                        AgentModelClient.buildUserHistoryMessage(normalized, emptyList()) +
                        result.transcript
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.READY,
                        response = result.content,
                        status = "已完成",
                    )
                } else {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.ERROR,
                        status = result.error ?: "Agent 执行失败",
                    )
                }
                true
            }
            if (shouldAcknowledge) runtimeClient.ackResult(runId)
        }
    }

    private fun handleRuntimeEvent(runId: String, event: AgentEvent) {
        if (activeRunId != runId) return
        if (AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)) {
            hiddenForForegroundOperation = true
            hide()
        }
        uiState = when (event) {
            is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                uiState.copy(response = uiState.response + event.delta)
            } else {
                uiState
            }
            is AgentEvent.ToolStarted -> uiState.copy(status = "Eta 正在执行操作")
            is AgentEvent.ProviderRequestStarted -> uiState.copy(status = "Eta 正在思考")
            is AgentEvent.RunFailed -> uiState.copy(
                phase = EtaVoicePhase.ERROR,
                status = event.reason,
            )
            else -> uiState
        }
    }

    private fun cancelCurrentRun() {
        val runId = activeRunId ?: return
        activeRunId = null
        runtimeClient.cancelRun(runId)
        runJob?.cancel()
        runJob = null
    }

    private fun stopCurrentRun() {
        if (activeRunId == null) return
        cancelCurrentRun()
        uiState = uiState.copy(
            phase = EtaVoicePhase.READY,
            status = "已停止",
        )
        inputFocusRequestKey++
    }

    private fun closeSession() {
        cancelCurrentRun()
        finish()
    }
}
