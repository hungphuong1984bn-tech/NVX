package vn.nvxconnect.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceStatus(val label: String) {
    INIT("Đang khởi tạo giọng nói"), READY("Giọng nói sẵn sàng"),
    NO_VIETNAMESE("Máy chưa có giọng đọc tiếng Việt. Cài dữ liệu giọng nói tiếng Việt trong Cài đặt Android > Chuyển văn bản thành giọng nói."),
    UNAVAILABLE("Máy không có bộ đọc giọng nói (TTS)")
}

/**
 * Đọc chỉ dẫn bằng TextToSpeech tiếng Việt, giảm âm lượng nhạc khi đọc (audio focus kiểu dẫn đường).
 * Nếu máy thiếu giọng tiếng Việt thì KHÔNG đọc (đọc bằng giọng sai ngôn ngữ sẽ không hiểu được) và báo trạng thái.
 */
class VoiceGuide(context: Context) : Speaker {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _status = MutableStateFlow(VoiceStatus.INIT)
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var seq = 0
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    init {
        tts = TextToSpeech(app) { st ->
            val t = tts
            if (st != TextToSpeech.SUCCESS || t == null) { _status.value = VoiceStatus.UNAVAILABLE; return@TextToSpeech }
            val r = t.setLanguage(Locale("vi", "VN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { _status.value = VoiceStatus.NO_VIETNAMESE; return@TextToSpeech }
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { release() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { release() }
            })
            _status.value = VoiceStatus.READY
        }
    }

    override fun speak(text: String) {
        val t = tts ?: return
        if (_status.value != VoiceStatus.READY) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        focus = req
        audio.requestAudioFocus(req)   // nếu không được cấp vẫn đọc: chỉ dẫn quan trọng hơn nhạc
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav-${seq++}")
    }

    private fun release() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }
    fun shutdown() { release(); tts?.stop(); tts?.shutdown(); tts = null }
}
