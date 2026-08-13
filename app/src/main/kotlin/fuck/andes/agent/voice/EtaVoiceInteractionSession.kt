package fuck.andes.agent.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View

/**
 * 系统数字助理的入口桥接。
 *
 * 系统会为本类创建 TYPE_VOICE_INTERACTION 窗口，但 Eta 的实际界面由自己的
 * TYPE_APPLICATION_OVERLAY 窗口承载，避免把厂商助手动画和输入层级绑定到系统会话窗口。
 */
internal class EtaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onCreateContentView(): View = View(context)

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        EtaAssistantOverlayService.show(context)
    }

    override fun onBackPressed() {
        EtaAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onCloseSystemDialogs() {
        EtaAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onDestroy() {
        // 浮窗拥有独立生命周期；系统会话重建不代表用户关闭了助理。
        super.onDestroy()
    }
}
