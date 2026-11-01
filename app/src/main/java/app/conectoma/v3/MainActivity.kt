package app.conectoma.v3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.conectoma.v3.engine.AutonomousLearnService
import app.conectoma.v3.ui.ConectomaRoot
import app.conectoma.v3.ui.theme.ConectomaTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null
    var onSpeech: ((String) -> Unit)? = null
    var onSpeechState: ((Boolean) -> Unit)? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permission.launch(missing.toTypedArray())
        startService(Intent(this, AutonomousLearnService::class.java))

        setContent {
            ConectomaTheme {
                ConectomaRoot(
                    onListen = { startListen() },
                    onStopListen = { stopListen() }
                )
            }
        }
    }

    private fun startListen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onSpeech?.invoke("reconhecimento de fala indisponível neste aparelho")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onSpeechState?.invoke(true)
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        onSpeechState?.invoke(false)
                    }
                    override fun onError(error: Int) {
                        onSpeechState?.invoke(false)
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) onSpeech?.invoke(text)
                        onSpeechState?.invoke(false)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("pt", "BR"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    private fun stopListen() {
        recognizer?.stopListening()
        onSpeechState?.invoke(false)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
