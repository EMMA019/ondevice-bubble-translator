# ondevice-bubble-translator

Android向けオンデバイス翻訳。Phase 1でエンジン検証、Phase 2でバブルオーバーレイ。

## フェーズ

1. **Phase 1** — テキスト入力 → 端末内翻訳（ML Kit / Local LLM）
2. **Phase 2（いま）** — 画面キャプチャ OCR → 翻訳 → フローティングバブル表示

## Phase 2 の使い方（Pixel）

1. アプリを開く
2. **Start bubble overlay**
3. 「他のアプリの上に表示」を許可
4. 画面収録（MediaProjection）を許可
5. 右上の浮遊パネル **訳** をタップ → 画面上の英文をOCRして日本語バブル表示
6. バブルをタップすると原文 / 訳文を切り替え（デフォルトは訳文）
7. **消** でバブル消去、**×** またはアプリ内 Stop で終了

任意: 設定 → ユーザー補助 で `TranslateAccessibilityService` を有効化（ノード読み取り用。MVPの主経路はOCR）。

## ビルド

```bash
cd D:\\program\\ondevice-bubble-translator
.\\gradlew.bat :app:installDebug
```

`local.properties` に `sdk.dir=` が必要。

## 構成

| 役割 | 技術 |
|---|---|
| OCR | ML Kit Text Recognition |
| 翻訳 | TranslationEngine（既定 ML Kit、Local LLMはモデル任意） |
| 画面取得 | MediaProjection |
| 表示 | WindowManager overlay bubbles |
| 操作 | 浮遊コントロール（訳 / 消 / ×） |

常時毎フレーム翻訳はしない（電池対策）。タップしたときだけ実行。

## Local LLM

`files/models/gemma.task` を置けばPhase 1のLocal LLMエンジンが有効。Phase 2オーバーレイは当面ML Kit翻訳を使用。

## パッケージ

`com.emma019.ondevicebubble`
