package fuck.andes.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fuck.andes.agent.voice.EtaAssistantOverlayService
import fuck.andes.ui.app.AgentAppRoot
import fuck.andes.ui.app.AgentAppTheme

class MainActivity : ComponentActivity() {
    private var assistantConversationKey by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateAssistantHandoff(intent)
        setContent {
            AgentAppTheme {
                AgentAppRoot(
                    assistantConversationKey = assistantConversationKey,
                    onAssistantConversationOpened = { opened ->
                        assistantConversationKey = null
                        if (opened) {
                            EtaAssistantOverlayService.notifyHandoffReady(this)
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION) return
        assistantConversationKey = intent.getStringExtra(
            EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
        )?.takeIf(String::isNotBlank)
    }
}
