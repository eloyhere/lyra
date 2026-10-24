# 语音识别引擎调研

## 已接入：SenseVoiceSmall（完全离线）

Lyra 现在默认优先使用**端侧 SenseVoiceSmall int8**，模型不存在时回退系统识别。

- 运行时：sherpa-onnx `v1.13.6`（JitPack AAR，Apache-2.0），JNI 直接跑 ONNX Runtime。
- 模型：SenseVoiceSmall int8，约 **228 MB**，从 hf-mirror / HuggingFace 按需下载到
  `filesDir/asr/sense-voice/`；音频 16 kHz 单声道 PCM16，2 线程解码。
- 隐私：录音只在内存里，识别完全在设备上完成，音频不出设备，不需要任何账号或 Key。
- 回退：模型未下载、被删除或加载失败时，`VoiceController` 自动回到系统
  `SpeechRecognizer`；设置页可以随时下载或删除模型。
- 语言：中英日韩粤（`zh/en/ja/ko/yue`），使用 `auto` 自动判别。
- 速度：官方 RK3588 数据 1 线程 RTF 0.099（A76）/ 0.436（A55），2 线程更快；
  短句一般 0.5–2 秒出结果。
- 代价：首次下载 228 MB；加载后常驻内存约 300–400 MB；不支持藏语/蒙古语。
- APK：sherpa-onnx 只保留 `arm64-v8a` / `armeabi-v7a` 原生库，debug APK 约 113 MB；
  模型不打包进 APK。
- 许可：见根目录 [NOTICE.md](../NOTICE.md)。
- 真机验证：把官方 `zh.wav` 推进应用目录后用调试入口解码，得到
  「开饭时间早上9点至下午5点。」（ITN 已开启）。
- 许可证：SenseVoice 模型 MIT（FunASR），sherpa-onnx Apache-2.0，
  ONNX Runtime MIT。

> Qwen3-ASR-Flash 未接入：它按音频时长计费（$0.000035/秒 ≈ $0.126/小时），
> 需要阿里云 DashScope 账号和 Key；在「不引入账号」的前提下，离线模型更合适。

当前实现走 Android 的 `SpeechRecognizer`（系统语音服务）作为兜底。它的准确率完全
取决于设备上安装/默认的识别引擎：Pixel 上通常是 Google 语音服务，国内 ROM 上
可能是厂商自带引擎或第三方引擎；没有引擎时直接不可用。App 无法控制它的模型、
语言模型和方言能力，所以「按住说话」的识别质量在不同手机上差异很大。

下面是比较过的几条路线，结论在最前面。

## 结论

| 场景 | 推荐 | 理由 |
|---|---|---|
| 追求中文 / 方言准确率，能联网 | **Qwen3-ASR-Flash**（DashScope，OpenAI 兼容） | 中文公开基准 WER 很低，支持 22 种中文方言（含四川话），已有「通义千问」服务商可直接复用 Key |
| 隐私优先 / 离线 | **sherpa-onnx + SenseVoiceSmall int8** | 完全端侧，支持中英日韩粤，模型约 230 MB，需要首次下载 |
| 最轻的离线兜底 | sherpa-onnx 中文流式 Zipformer small int8（约 25 MB）或 Vosk 中文 small（约 42 MB） | 体积小、可离线，准确率低于 SenseVoice/Qwen |
| 零配置兜底 | 继续用系统 `SpeechRecognizer` | 不增加体积和 Key；准确率取决于手机 |

**建议的接入顺序**：系统识别保底 → 当前服务商是「通义千问」时自动用
Qwen3-ASR-Flash → 后续再加一个「离线高精度」开关（SenseVoice 按需下载模型）。

## 候选对比

| 引擎 | 形态 | 中文/方言 | 体积 / 依赖 | 隐私 | 接入成本 |
|---|---|---|---|---|---|
| 系统 `SpeechRecognizer` | 系统服务 | 取决于引擎 | 0 | 取决于引擎（可能上云） | 已接入 |
| Qwen3-ASR-Flash | 云端 API | 中文、粤语、22 种方言（含四川、闽南、吴语） | 无；需 DashScope Key | 音频离开设备，直连阿里云 | 中：录音 + base64 JSON |
| OpenAI `gpt-4o-transcribe` / `whisper-1` | 云端 API | 多语种，中文方言覆盖弱于 Qwen | 无；需 OpenAI Key | 音频离开设备 | 中：录音 + multipart |
| sherpa-onnx + SenseVoiceSmall int8 | 端侧 | 中英日韩粤，带情感/事件标签 | 模型约 230 MB | 音频不出设备 | 高：JNI/AAR + 模型下载 |
| sherpa-onnx Paraformer / Zipformer | 端侧 | 中文（流式） | 约 25–213 MB | 音频不出设备 | 高：同上 |
| Vosk small | 端侧 | 中文/英文等 | 中文约 42 MB | 音频不出设备 | 中：JNI + 模型 |
| whisper.cpp tiny/base | 端侧 | 多语种，中文一般 | 74 / 141 MB | 音频不出设备 | 高：JNI + 模型 |

### Qwen3-ASR-Flash

- 官方公开数据：Qwen3-ASR-1.7B 在 Fleurs-zh 离线 WER **2.41%**，0.6B 为 **2.88%**；
  中文方言集（22 种）WER 约 **15.94–18.24%**，明显优于 Whisper-large-v3 的
  **44.55–47.70%**（[Qwen3-ASR 模型卡 / 技术报告](https://pypi.org/project/qwen-asr/)）。
- API 支持 OpenAI 兼容协议：`POST /compatible-mode/v1/chat/completions`，
  `messages[].content` 里放 `input_audio` 的 base64 data URL；模型名
  `qwen3-asr-flash`，可用 `asr_options.language` 指定 `zh`/`yue`/`en`/`ja`/`ko` 等，
  `enable_itn` 打开数字/标点规范化（[阿里云文档](https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference)）。
- App 的「通义千问」服务商 base URL 正好是
  `https://dashscope.aliyuncs.com/compatible-mode/v1`，现有 `HttpTransport` 能直接发；
  不用引入新依赖，也不用多存一个 Key。
- 限制：必须联网；音频会上传到 DashScope；不支持藏语/蒙古语。

### 端侧 sherpa-onnx + SenseVoiceSmall

- sherpa-onnx 是 k2-fsa 的端侧推理运行时，官方支持 Android/iOS/Kotlin/C++ 等；
  SenseVoiceSmall 的 int8 ONNX 约 230 MB，支持中英日韩粤，并能给出情感/音频事件
  （[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、
  [SenseVoice](https://github.com/FunAudioLLM/SenseVoice)）。
- 优点：音频不出设备，不依赖 Key、不依赖网络；缺点：首次要下载模型，低端机
  推理有延迟和发热，中文方言能力不如 Qwen。
- 更轻的替代：中文流式 Zipformer small int8 约 25 MB、Paraformer int8 约 213 MB、
  Vosk 中文 small 约 42 MB。体积和准确率的取舍见
  [端侧 STT 选型对比](https://crawpress.com/articles/choosing-an-on-device-speech-to-text-model-funasr-whisper-vosk-xiaomi-mimo-and-the-open-asr-field)。

### 云端 OpenAI / Gemini

- `gpt-4o-transcribe` / `whisper-1` 走 `POST /v1/audio/transcriptions`（multipart），
  需要给 `HttpTransport` 增加 multipart 支持。
- Gemini 可以用 `generateContent` 内联 base64 音频，App 的 Gemini 客户端已有协议，
  但当前模型配置是文本模型，要单独选音频模型。
- 中文方言（尤其四川话）不是它们的强项，故排在 Qwen 之后。

## 与本项目约束的对齐

- 「设备直连服务商、没有中转服务器」：Qwen/OpenAI/Gemini 云端 ASR 都符合，
  但音频会离开设备，需要在设置里写清楚（和现在把屏幕元素发给模型是同一类数据）。
- 「用户自己的 Key」：复用当前服务商的 Key，不新增账号；代价是同一时间只有
  一个 Key（选 DeepSeek 时无法用 Qwen ASR）。
- 「自用构建」：可以先做 Qwen 云端路径，端侧 SenseVoice 作为后续隐私选项。

## 建议的接入方式（Qwen 优先）

1. `VoiceRecorder`：按住时用 `AudioRecord` 录 16 kHz / 单声道 / PCM16，松手后封 WAV；
2. `QwenAsrClient`：把 WAV 转 base64 data URL，POST
   `{base}/chat/completions`，解析 `choices[0].message.content`（兼容字符串或
   `[{text: ...}]` 数组）；
3. 引擎选择：当前服务商是 `qwen` 且 Key 存在 → Qwen ASR；否则系统识别；
4. 失败回退：网络/Key/额度错误时提示原因，保留系统识别作为手动重试路径；
5. 隐私说明：在「语音识别」设置项里注明「音频会发送给当前模型服务商」。

端侧 SenseVoice 的接入方式：sherpa-onnx AAR + 首次使用下载模型到
`filesDir/models/`，下载前明确体积和流量提示；识别完全本地，适合作为
「离线 / 隐私」选项。
