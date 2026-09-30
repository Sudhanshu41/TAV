package com.example.teachablevoice.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.example.teachablevoice.agent.AgentForegroundService
import com.example.teachablevoice.agent.AgentStateRepository
import com.example.teachablevoice.agent.AgentStatus
import com.example.teachablevoice.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The actual voice session that runs when the system invokes TAV.
 *
 * Responsibilities:
 * - Start AudioRecord to capture voice.
 * - Connect to Gemini Live STT to get transcript.
 * - Route the command through AgentRequestRouter.
 * - Launch AgentForegroundService.
 */
class TAVVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "TAVVoiceSession"
        private const val RESTART_DELAY_MS = 1500L
        private const val HARD_LISTENING_TIMEOUT_MS = 7000L
    }

    private val voiceState = VoiceStateRepository.globalState
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioCaptureManager: AudioCaptureManager? = null
    private var geminiSttClient: GeminiLiveSttClient? = null
    
    private var accumulatedCommand = ""
    private var hardTimeoutRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        window.window?.attributes?.alpha = 0f
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Session shown, starting command capture")
        
        accumulatedCommand = ""
        voiceState.transitionTo(VoiceListenerState.LISTENING)
        
        setupGeminiAndAudio()
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "Session hidden")
        stopCaptureAndClient()
        if (voiceState.current.state != VoiceListenerState.AGENT_RUNNING) {
            voiceState.transitionTo(VoiceListenerState.IDLE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCaptureAndClient()
        handler.removeCallbacksAndMessages(null)
    }

    private fun setupGeminiAndAudio() {
        geminiSttClient = GeminiLiveSttClient(object : GeminiLiveSttClient.Listener {
            override fun onConnected() {
                Log.i(TAG, "Gemini STT connected, starting audio capture")
                audioCaptureManager?.start()
            }

            override fun onTranscript(text: String, isFinal: Boolean) {
                accumulatedCommand = if (accumulatedCommand.isEmpty()) text else "$accumulatedCommand $text"
                voiceState.updatePartialText(accumulatedCommand)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Gemini STT error: $error")
                voiceState.setError("STT error: $error")
                handler.postDelayed({ hide() }, RESTART_DELAY_MS)
            }

            override fun onClosed() {
                Log.i(TAG, "Gemini STT closed")
            }
        })
        
        audioCaptureManager = AudioCaptureManager(object : AudioCaptureManager.AudioCaptureListener {
            override fun onAudioData(data: ByteArray, size: Int) {
                geminiSttClient?.sendAudio(data, size)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Audio capture error: $error")
            }
        })

        geminiSttClient?.connect()

        hardTimeoutRunnable?.let { handler.removeCallbacks(it) }
        hardTimeoutRunnable = Runnable {
            if (voiceState.current.state == VoiceListenerState.LISTENING) {
                Log.i(TAG, "7-second hard timeout reached, processing whatever was heard")
                audioCaptureManager?.stop()
                geminiSttClient?.sendEndOfAudio()
                
                // Give a short delay to allow final transcript chunks to arrive, then process
                handler.postDelayed({
                    if (voiceState.current.state == VoiceListenerState.LISTENING) {
                        finalizeCommand()
                    }
                }, 500)
            }
        }
        handler.postDelayed(hardTimeoutRunnable!!, HARD_LISTENING_TIMEOUT_MS)
    }

    private fun stopCaptureAndClient() {
        audioCaptureManager?.stop()
        geminiSttClient?.close()
        audioCaptureManager = null
        geminiSttClient = null
        hardTimeoutRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun finalizeCommand() {
        if (voiceState.current.state != VoiceListenerState.LISTENING) return
        
        hardTimeoutRunnable?.let { handler.removeCallbacks(it) }
        val finalCommand = accumulatedCommand.trim()
        
        if (finalCommand.isEmpty()) {
            voiceState.setError("No command heard")
            handler.postDelayed({ hide() }, RESTART_DELAY_MS)
            return
        }

        voiceState.transitionTo(VoiceListenerState.TRANSCRIBING)
        stopCaptureAndClient()
        
        processCommand(finalCommand)
    }

    private fun processCommand(command: String) {
        voiceState.setTranscription(command)
        voiceState.transitionTo(VoiceListenerState.PROCESSING)
        
        AgentStateRepository.globalState.appendLog("VOICE", "Heard: \"$command\"")

        scope.launch {
            try {
                ModelManager.init(context)
                if (!ModelManager.backend.isLoaded()) {
                    ModelManager.backend.load()
                }

                val router = AgentRequestRouter(context, ModelManager.backend)
                val request = router.route(command)

                if (request == null) {
                    voiceState.setError("Couldn't understand that command")
                    AgentStateRepository.globalState.appendLog("VOICE", "Failed to parse command", isError = true)
                    handler.postDelayed({ hide() }, RESTART_DELAY_MS)
                    return@launch
                }

                Log.i(TAG, "Routed: app=${request.targetApp}, task=${request.task}")
                AgentStateRepository.globalState.appendLog("VOICE", "→ ${request.targetApp}: ${request.task}")

                val appLabel = request.targetApp.substringAfterLast(".")
                voiceState.setRouting(appLabel, request.task)

                voiceState.transitionTo(VoiceListenerState.AGENT_RUNNING)
                launchAgent(request)
                
                monitorAgentCompletion()

            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}", e)
                voiceState.setError("Error: ${e.message}")
                AgentStateRepository.globalState.appendLog("VOICE", "Error: ${e.message}", isError = true)
                handler.postDelayed({ hide() }, RESTART_DELAY_MS)
            }
        }
    }

    private fun launchAgent(request: AgentRequestRouter.AgentRequest) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            putExtra(AgentForegroundService.EXTRA_OBJECTIVE, request.task)
            putExtra(AgentForegroundService.EXTRA_TARGET_APP, request.targetApp)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun monitorAgentCompletion() {
        scope.launch {
            AgentStateRepository.globalState.stateFlow.collect { agentState ->
                if (voiceState.current.state == VoiceListenerState.AGENT_RUNNING && !agentState.isRunning) {
                    if (agentState.status == AgentStatus.COMPLETED) {
                        AgentStateRepository.globalState.appendLog("VOICE", "✓ Task complete.")
                        voiceState.transitionTo(VoiceListenerState.DONE)
                    } else if (agentState.status == AgentStatus.ERROR) {
                        AgentStateRepository.globalState.appendLog("VOICE", "✗ Task failed.")
                        voiceState.transitionTo(VoiceListenerState.ERROR)
                    }
                    
                    handler.postDelayed({ hide() }, 1000)
                    return@collect
                }
            }
        }
    }
}
