# 第三方组件与许可

Lyra 在构建时或运行时使用以下开源组件。分发 App 时请保留本文件与各组件的许可证。

| 组件 | 版本 / 形态 | 许可证 | 用途 |
|---|---|---|---|
| sherpa-onnx | `v1.13.6` AAR（JitPack） | Apache-2.0 | 端侧语音识别运行时 / JNI |
| ONNX Runtime | 随 sherpa-onnx AAR 打包 | MIT | 推理后端 |
| SenseVoiceSmall int8 | 运行时下载，约 228 MB | MIT（FunASR / SenseVoice） | 端侧中英日韩粤语音识别模型 |
| Material Color Utilities | 仅构建期生成配色 | Apache-2.0 | 静态主题色阶生成 |

SenseVoiceSmall 模型下载地址：

- https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
- https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17

模型只在用户主动点击「下载」后才写入 `filesDir/asr/sense-voice/`，不随 APK 分发；
删除模型后 App 自动回退到系统语音识别。
