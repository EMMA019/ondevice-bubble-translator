# ondevice-bubble-translator

Android向けオンデバイス翻訳。**最初から訳されている**体験を目指す。

## 強み（v0.4）

- **常時訳表示（自動ON）**: 画面が落ち着いたら Accessibility でテキスト取得 → 言語判定 → 日本語バブル
- **原文はタップで戻す**（レア操作）
- **端末内・枠なし**（ML Kit）
- 多言語ソース対応

## 使い方

1. 設定 → ユーザー補助で本アプリ ON
2. **Start bubble overlay**（重ねて表示を許可）
3. 浮遊パネル **自動ON** のまま他アプリを開く → 自動でバブル更新
4. **訳** で手動実行、**消** でクリア、**自動OFF** で手動のみ

## 制限

- Chrome等はアクセシビリティツリーが薄いと取りこぼす
- 画像内文字は対象外
- 初回言語ペアはダウンロードあり

## ビルド

```bash
cd D:\\program\\ondevice-bubble-translator
git pull
.\\gradlew.bat :app:installDebug
```
