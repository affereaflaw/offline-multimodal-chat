# Offline Multimodal Chat

> **Note**: This project is a fork of [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery), streamlined and enhanced for a focused, on-device AI experience (WITH VIBES).

**Offline Multimodal Chat** is an Android application designed to demonstrate the power of on-device Large Language Models (LLMs). Built using the MediaPipe LLM Inference API and Gemini Nano/Gemma models, it allows you to chat with an AI entirely offline—ensuring privacy and low latency.

## Key Features

*   **🔒 Privacy-First & Offline**: All processing happens locally on your device. No data is sent to the cloud, and no internet connection is required for inference.
*   **💬 Multimodal Capabilities**: Interact naturally using **Text**, **Images**, and **Audio**.
*   **🎙️ Live Streaming Mode**: Experience a continuous, hands-free conversation with the AI.
    *   **Real-time Audio & Vision**: The AI can "see" through your camera and "hear" you speak in real-time.
    *   **Ring Buffer Audio**: Uses a smart 15-second audio buffer to capture context efficiently.
*   **⚡ Optimized Performance**:
    *   **Stateless Sessions**: Ensures stable performance over long interactions by managing context windows effectively.
    *   **Fast Inference**: Powered by LiteRT (TensorFlow Lite) and GPU acceleration.
*   **✨ Simplified Experience**: A clean, simplified UI dedicated purely to the chat experience, removing legacy experimental features for a production-ready feel.

## Getting Started

1.  **Download a Model**: On first launch, the app will guide you to download a supported `.bin` or `.task` model (e.g., Gemma 2 2b or Gemma 3n).
2.  **Start Chatting**: reliable text and image chat.
3.  **Go Live**: Switch to "Live Streaming" mode for a seamless voice and video conversation.

## Architecture

This app simplifies the original Gallery codebase by:
*   Consolidating "Voice Command" and "Live Audio" into a single, robust **Live Streaming** feature.
*   Removing unused experimental tasks (Prompt Lab, Tiny Garden, etc.).
*   Streamlining navigation to get you straight to the chat.

## Disclaimer

This is an open-source demonstration project. While it uses Google's AI Edge tools, it is a simplified fork maintained for educational and experimental purposes.
