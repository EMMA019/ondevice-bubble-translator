# ondevice-bubble-translator

Android向けオンデバイス翻訳。**最初から訳されている**体験 + ハイブリッド品質。

## v0.5 ハイブリッド

| 操作 | エンジン |
|---|---|
| **自動ON**（画面変化） | ML Kit（速い） |
| **訳**（手動） | ML Kit + 長文は Local LLM（`gemma.task` があるとき） |
| **磨** | いまのバブルを Local LLM で磨き直し |

モデル配置: アプリの `files/models/gemma.task`（MediaPipe用）。無い場合は全部 ML Kit にフォールバック。

## 使い方

1. ユーザー補助で本アプリ ON
2. Start bubble overlay
3. 自動でML Kit訳が表示される
4. 気になる画面で **訳** / **磨**（LLMあり時）

## ビルド

```bash
cd D:\\program\\ondevice-bubble-translator
git pull
.\\gradlew.bat :app:installDebug
```
