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
  val sttState by holdToDictateViewModel.uiState.collectAsState()
  val chatUiState by viewModel.uiState.collectAsState()
  val scope = androidx.compose.runtime.rememberCoroutineScope()

   // Holder for the latest camera frame. Use a plain object to avoid frequent recompositions.
  val latestBitmapHolder = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicReference<Bitmap?>(null) }

  // Expose TTS state (assuming ViewModel exposes it, or we hack access via property)
  // Since ttsManager is in Base, and we initiated it, we can access it.
  // But strictly speaking, we should expose flow from ViewModel.
  // Let's assume we can access viewModel.ttsManager directly for valid flow since it's open.
  // Or better, let's just collect it if we modify ViewModel.
  // But I didn't modify ViewModel to expose `isSpeaking` Flow yet. I modified TtsManager.
  // So I can access `viewModel.ttsManager?.isSpeaking` if I cast or access property.
  // Waiting for that... checking LlmChatViewModel.kt again... ttsManager is public open var.
  
  val isTtsSpeaking = viewModel.ttsManager?.isSpeaking?.collectAsState()?.value ?: false
  
  var retryTrigger by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
  
  // Logic: Wait for TTS to finish. Then wait 2 seconds. Then listen.
  
  androidx.compose.runtime.LaunchedEffect(isContinuousVoiceMode, chatUiState.inProgress, sttState.recognizing, isTtsSpeaking, retryTrigger) {
      if (isContinuousVoiceMode && !chatUiState.inProgress && !sttState.recognizing) {
          if (isTtsSpeaking) {
              // Do nothing, wait for TTS to finish
          } else {
              // TTS finished or not speaking.
              // Wait 2 seconds (user requested delay).
              // But we don't want to delay *every* time the loop checks.
              // We only want to delay *after* TTS finishes or *after* generation finishes.
              // Simple heuristic: always delay before listing?
              // "change listen again after tts finish... add delay too 2s"
              kotlinx.coroutines.delay(2000)
              
              // Double check state after delay
              if (isContinuousVoiceMode && !chatUiState.inProgress && !sttState.recognizing && !(viewModel.ttsManager?.isSpeaking?.value ?: false)) {
                   holdToDictateViewModel.startSpeechRecognition(
                      onAmplitudeChanged = {},
                      onDone = { text ->
                          if (text.isNotBlank()) {
                              val model = modelManagerViewModel.uiState.value.selectedModel
                              val message = ChatMessageText(content = text, side = ChatSide.USER)
                              viewModel.addMessage(model = model, message = message)
                              modelManagerViewModel.addTextInputHistory(text)
                              
                               // Capture the latest frame
                              val currentImage = latestBitmapHolder.get()
                              val images = if (currentImage != null) listOf(currentImage) else listOf()

                              viewModel.generateResponse(
                                  model = model,
                                  input = text,
                                  images = images,
                                  onError = {
                                     viewModel.handleError(context, task, model, modelManagerViewModel, it)
                                  }
                              )
                          } else {
                              // Silence. Retry.
                              retryTrigger = System.currentTimeMillis()
                          }
                      }
                  )
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
            holdToDictateViewModel.stopSpeechRecognition()
        },
        navigateUp = navigateUp,
        modifier = Modifier.fillMaxSize(), // ChatView takes full space
        isContinuousVoiceMode = isContinuousVoiceMode,
        onToggleVoiceMode = { 
            isContinuousVoiceMode = !isContinuousVoiceMode 
            if (!isContinuousVoiceMode) {
                holdToDictateViewModel.stopSpeechRecognition()
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
              
              if (sttState.recognizing) {
                  androidx.compose.foundation.layout.Box(
                      modifier = Modifier
                          .fillMaxSize()
                          .background(Color.Green.copy(alpha = 0.3f))
                  ) {
                       androidx.compose.material3.Text(
                          text = "Speak Now",
                          color = Color.White,
                          style = MaterialTheme.typography.labelSmall,
                          modifier = Modifier
                              .align(Alignment.BottomCenter)
                              .padding(4.dp)
                              .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                              .padding(horizontal = 4.dp, vertical = 2.dp)
                      )
                  }
              }
          }
      }
  }
}
