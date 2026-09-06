# ondevice-bubble-translator

Android向けオンデバイス翻訳。画面のテキストを掴んで日本語へ。

## いまの主経路（v0.3）

**Accessibility でテキスト取得 → 言語自動判定 → ML Kit で日本語翻訳 → バブル表示**

スクショOCRは使わない（遅い／固まるため）。

## 使い方

1. 設定 → **ユーザー補助** で `OnDevice Translate Lab` をON
2. アプリで **Start bubble overlay**（他アプリの上に表示を許可）
3. 翻訳したい画面を開いて浮遊の **訳**
4. バブルタップで原文に戻せる

対応の目安（ML Kitが持つ言語パック）: en / zh / ko / fr / de / es / it / pt / ru / th / vi / hi / id / tr / pl / nl / sv / ar など → `ja`

日本語と判定された文言はスキップ。初回の言語ペアはダウンロードあり（以降オフライン）。

## 制限

- Chrome等のWebはアクセシビリティツリーが薄いことがあり、全部は取れない場合あり（Chrome内蔵翻訳のほうが速い理由）
- 画像の中の文字は取れない（OCRは将来のフォールバック）

## ビルド

```bash
cd D:\\program\\ondevice-bubble-translator
git pull
.\\gradlew.bat :app:installDebug
```

## パッケージ

`com.emma019.ondevicebubble`
