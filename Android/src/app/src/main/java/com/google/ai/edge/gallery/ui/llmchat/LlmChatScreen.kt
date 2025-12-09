/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import androidx.hilt.navigation.compose.hiltViewModel
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun LlmChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmChatViewModel = hiltViewModel(),
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_CHAT,
    navigateUp = navigateUp,
    modifier = modifier,
  )
}

@Composable
fun LlmAskImageScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskImageViewModel = hiltViewModel(),
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_IMAGE,
    navigateUp = navigateUp,
    modifier = modifier,
  )
}

@Composable
fun LlmAskAudioScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskAudioViewModel = hiltViewModel(),
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_AUDIO,
    navigateUp = navigateUp,
    modifier = modifier,
  )
}

@Composable
fun ChatViewWrapper(
  viewModel: LlmChatViewModelBase,
  modelManagerViewModel: ModelManagerViewModel,
  taskId: String,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val task = modelManagerViewModel.getTaskById(id = taskId)!!
  val holdToDictateViewModel: com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel = hiltViewModel()
  var isContinuousVoiceMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  // Removed isLiveAudioMode toggle
  
  val sttState by holdToDictateViewModel.uiState.collectAsState()
  val chatUiState by viewModel.uiState.collectAsState()
  val scope = androidx.compose.runtime.rememberCoroutineScope()

   // Holder for the latest camera frame.
  val latestBitmapHolder = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicReference<Bitmap?>(null) }
  
  // Audio Recorder
  val audioRecorder = androidx.compose.runtime.remember { com.google.ai.edge.gallery.ui.common.AudioRecorderManager() }
  val audioAmplitude by audioRecorder.amplitude.collectAsState()

  // Expose TTS state
  val isTtsSpeaking = viewModel.ttsManager?.isSpeaking?.collectAsState()?.value ?: false
  
  var retryTrigger by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
  
  // Logic: Wait for TTS to finish. Then wait 2 seconds. Then listen (via Audio Recorder).
  
  androidx.compose.runtime.LaunchedEffect(isContinuousVoiceMode, chatUiState.inProgress, isTtsSpeaking, retryTrigger) {
      if (isContinuousVoiceMode && !chatUiState.inProgress) {
          if (isTtsSpeaking) {
              // Wait for TTS
          } else {
              kotlinx.coroutines.delay(2000)
              
              if (isContinuousVoiceMode && !chatUiState.inProgress && !(viewModel.ttsManager?.isSpeaking?.value ?: false)) {
                   // Always use Live Audio Recording (Multimodal)
                   audioRecorder.startRecording()
                   
                   // VAD Logic
                   var silenceDuration = 0L
                   var speechDetected = false
                   val startTime = System.currentTimeMillis()
                   
                   while (isActive && isContinuousVoiceMode) {
                       kotlinx.coroutines.delay(100)
                       val ampl = audioAmplitude // RMS from recorder
                       if (ampl > 2000) { // Threshold
                           speechDetected = true
                           silenceDuration = 0
                       } else {
                           if (speechDetected) {
                               silenceDuration += 100
                           }
                       }
                       
                       // Timeout (max 15s matching buffer or just 10s interaction) or Silence (1.5s)
                       // If we wait > 15s we lose start of audio in ring buffer, but that's intended (last 15s).
                       if ((speechDetected && silenceDuration > 1500) || (System.currentTimeMillis() - startTime > 15000)) {
                           break
                       }
                   }
                   
                   val audioData = audioRecorder.stopRecording()
                   // Send if we detected speech OR if user just wants to capture "ambience" (maybe threshold check?)
                   // User said "differentiate prompt from voice", implied mixed content. 
                   // If we only send when speechDetected, we might miss "just music". 
                   // BUT for "Start -> Stop -> Send", we likely want some trigger.
                   // Let's stick to speech/sound trigger for now to avoid spamming empty rooms.
                   // Actually, if I listen to music, amplitude > 2000. So it works.
                   
                   if (audioData.isNotEmpty() && speechDetected) {
                        val model = modelManagerViewModel.uiState.value.selectedModel
                        val audioClip = ChatMessageAudioClip(audioData, audioRecorder.getSampleRate(), ChatSide.USER)
                        val audioMessages = listOf(audioClip)

                        // Capture frame
                        val currentImage = latestBitmapHolder.get()
                        val images = if (currentImage != null) listOf(currentImage) else listOf()
                        
                        viewModel.addMessage(model = model, message = audioClip)
                        
                        // Send audio-only (multimodal)
                        viewModel.generateResponse(
                              model = model,
                              input = " ",
                              images = images,
                              audioMessages = audioMessages,
                              onError = {
                                 viewModel.handleError(context, task, model, modelManagerViewModel, it)
                              }
                        )
                   } else {
                       retryTrigger = System.currentTimeMillis()
                   }
              }
          }
      }
  }

  Box(modifier = modifier.fillMaxSize()) {
      ChatView(
        task = task,
        viewModel = viewModel,
        modelManagerViewModel = modelManagerViewModel,
        onSendMessage = { model, messages ->
          for (message in messages) {
            viewModel.addMessage(model = model, message = message)
          }
    
          var text = ""
          val images: MutableList<Bitmap> = mutableListOf()
          val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
          var chatMessageText: ChatMessageText? = null
          for (message in messages) {
            if (message is ChatMessageText) {
              chatMessageText = message
              text = message.content
            } else if (message is ChatMessageImage) {
              images.addAll(message.bitmaps)
            } else if (message is ChatMessageAudioClip) {
              audioMessages.add(message)
            }
          }
          if ((text.isNotEmpty() && chatMessageText != null) || audioMessages.isNotEmpty()) {
            modelManagerViewModel.addTextInputHistory(text)
            viewModel.generateResponse(
              model = model,
              input = text,
              images = images,
              audioMessages = audioMessages,
              onError = { errorMessage ->
                viewModel.handleError(
                  context = context,
                  task = task,
                  model = model,
                  modelManagerViewModel = modelManagerViewModel,
                  errorMessage = errorMessage,
                )
              },
            )
    
            firebaseAnalytics?.logEvent(
              "generate_action",
              bundleOf("capability_name" to task.id, "model_id" to model.name),
            )
          }
        },
        onRunAgainClicked = { model, message ->
          if (message is ChatMessageText) {
            viewModel.runAgain(
              model = model,
              message = message,
              onError = { errorMessage ->
                viewModel.handleError(
                  context = context,
                  task = task,
                  model = model,
                  modelManagerViewModel = modelManagerViewModel,
                  errorMessage = errorMessage,
                )
              },
            )
          }
        },
        onBenchmarkClicked = { _, _, _, _ -> },
        onResetSessionClicked = { model -> viewModel.resetSession(task = task, model = model) },
        showStopButtonInInputWhenInProgress = true,
        onStopButtonClicked = { model -> 
            viewModel.stopResponse(model = model)
            isContinuousVoiceMode = false // Stop voice mode if user manually stops
            audioRecorder.stopRecording() // Ensure stop
        },
        navigateUp = navigateUp,
        modifier = Modifier.fillMaxSize(), // ChatView takes full space
        isContinuousVoiceMode = isContinuousVoiceMode,
        onToggleVoiceMode = { 
            isContinuousVoiceMode = !isContinuousVoiceMode 
            if (!isContinuousVoiceMode) {
                audioRecorder.stopRecording()
            }
        }
      )

      // Live Camera Preview (Pip)
      if (isContinuousVoiceMode) {
          Box(
              modifier = Modifier
                  .align(Alignment.TopEnd)
                  .padding(top = 80.dp, end = 16.dp) // Offset from top bar
                  .size(width = 120.dp, height = 160.dp)
                  .background(Color.Black, MaterialTheme.shapes.medium)
                  .clip(MaterialTheme.shapes.medium)
                  .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
          ) {
              LiveCameraView(
                  cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
                  onBitmap = { bmp, proxy ->
                      latestBitmapHolder.set(bmp)
                      proxy.close()
                  },
                  modifier = Modifier.fillMaxSize()
              )
              
              // Simple Indicator (Red for recording)
               androidx.compose.foundation.layout.Box(
                  modifier = Modifier.fillMaxSize()
              ) {
                   androidx.compose.material3.Text(
                      text = "Transmitting...",
                      color = Color.Red,
                      style = MaterialTheme.typography.labelSmall,
                      modifier = Modifier
                          .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                          .padding(horizontal = 4.dp, vertical = 2.dp)
                  )
              }
          }
      }
  }
}
