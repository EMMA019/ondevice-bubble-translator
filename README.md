# ondevice-bubble-translator

Android向けオンデバイス翻訳の実験リポジトリ。

## フェーズ

1. **Phase 1（いま）** — テキスト入力 → 端末内翻訳の検証アプリ。ML Kit Translation を基準線にし、差し替え可能なローカル高精度エンジン枠を用意。
2. **Phase 2（後）** — ML Kit OCR + WindowManager バブルオーバーレイ + AccessibilityService。

## 方針

- サーバーなし / 有料翻訳APIなし（主経路）
- 翻訳エンジンはインターフェースで差し替え
- 前処理（改行正規化・文結合）は共通
- 常時フル推論は電池死するので、Phase 2では停止後／範囲選択を想定

## ビルド

1. Android Studio (Ladybug+) でこのリポを Open
2. Gradle Sync
3. 実機またはエミュレータで `app` を Run

Gradle Wrapper JAR が無い場合は Android Studio が生成するか、ローカルで:

```bash
gradle wrapper --gradle-version 8.9
```

## Phase 1 の使い方

1. 起動後、英語テキストを入力
2. エンジンを選ぶ
   - **ML Kit** — 初回のみ言語パックDL（要ネット）。以降オフライン可
   - **Local LLM** — `files/models/` に MediaPipe 用 `.task` モデルを置いたときのみ有効（README下部）
3. Translate → 日本語出力を確認。ML Kit と並べて qualitatively 比較する想定

## Local LLM モデル（任意）

MediaPipe LLM Inference 用の量子化モデル（例: Gemma 2B / 1B 系 `.task`）を端末へ配置:

1. 対応モデルを入手（Google AI Edge / MediaPipe の配布手順に従う）
2. アプリの内部ストレージ `files/models/gemma.task` へコピー（設定画面の「モデルパス」でも可）
3. エンジンで Local LLM を選択

目安: モデル 0.7–1.5GB、RAM 余裕がある端末推奨。常時バックグラウンド推論は非推奨。

## パッケージ

`com.emma019.ondevicebubble`

## ライセンス

Personal / experimental. 依存ライブラリは各ライセンスに従う。
