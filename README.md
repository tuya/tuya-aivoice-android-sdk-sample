
## Project Introduction

The **AI Voice UI BizBundle** is designed to upgrade standard audio products—such as Bluetooth headphones, smart glasses, and speakers—into AI-powered devices. It provides access to AI recording and translation features, utilizing professional recording algorithms combined with advanced language models. It supports real-time, accurate transcription and translation across more than 100 languages worldwide.

Before integrating this BizBundle, please complete the [Preparation](https://developer.tuya.com/en/docs/app-development/preparation?id=Ka8qhzjybzmko) and [Quick Integration](https://developer.tuya.com/en/docs/app-development/integrated?id=Ka69nt96cw0uj) steps.

AI Voice BizBundle Demo Link: [https://github.com/tuya/tuya-aivoice-android-sdk-sample.git](https://github.com/tuya/tuya-aivoice-android-sdk-sample.git).

---

## Native API Integration Guide

Besides the ready-made UI BizBundle, this project also exposes a **Native API**
(`ThingAudioDetectManagerNative`) so you can build your own UI on top of recording,
transcription, cloud sync and the other capabilities.

👉 **[Native API Integration Guide](./docs/modules/README.md)** (written in Chinese)

Split per capability module. Each document covers the API list, call sequence,
per-parameter reference, data structure field tables, error codes and an integration checklist:

| Module | Document |
|---|---|
| 1 · Recording control & device capability | [module-01](./docs/modules/module-01-record-control.md) |
| 2 · Transcription & summary | [module-02](./docs/modules/module-02-transcribe-summary.md) |
| 3 · File management | [module-03](./docs/modules/module-03-file-management.md) |
| 4 · Offline file transfer | [module-04](./docs/modules/module-04-offline-transfer.md) |
| 5 · Audio import | [module-05](./docs/modules/module-05-audio-import.md) |
| 6 · Cloud sync | [module-06](./docs/modules/module-06-cloud-sync.md) |
| 8 · Merge recordings | [module-08](./docs/modules/module-08-merge-record.md) |
| 9 · Share / floating ball / quick entry | [module-09](./docs/modules/module-09-share-and-entry.md) |
| 10 · Text translation | [module-10](./docs/modules/module-10-text-translation.md) |

Runnable samples live in [`ai_voice/.../nativeui`](./ai_voice/src/main/java/com/tuya/smart/ai_voice/nativeui);
every module document links to its own demo page.

---

## Brief Summary of Considerations

### 1. Preparation

- **Description**: Ensure that you have applied for the AppKey, AppSecretKey, AppID, signing components, etc., on the Tuya IoT Platform and integrated them into your project.
- **Documentation**: [Preparation for Integrating App SDK](https://developer.tuya.com/en/docs/app-development/integrated?id=Ka69nt96cw0uj)

### 2. Dependency Configuration

Repository setup, version baselines, the minimal Native dependency set and the
full UI BizBundle dependency set are maintained in one place:
[Dependency Configuration](./docs/nativesdk-dependency-guide.md) (written in Chinese).

- Native API only: use the "仅 Native 接入" section.
- Full AI Voice BizBundle: use the "完整 UI BizBundle 接入" section.
