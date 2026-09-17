# PhotoMapViewer

電波の届かない山岳・渓流・樹林帯などでのフィールド調査・記録に特化した、オフライン対応GIS＆高精度カメラAndroidアプリです。

国土地理院タイル地図の超高速オフラインキャッシュ、GPS衛星測位と地図追従の完全分離、地磁気偏角補正による「真北（True North）」EXIF直書き撮影機能を備えています。

---

## 🌲 主な機能と特徴

### 1. GPS測位と地図追従の完全分離
- **メイン電源（トップバー `[📡 GPS]`）:** 衛星測位の稼働スイッチ。フォアグラウンドサービス（`GpsForegroundService`）が常駐し、画面消灯・アプリ切替時も高精度測位のホットスタートを維持。
- **自動追従（地図上 `[◎]`）:** 地図の中心を現在地に固定するパン機能。手動ドラッグ時は追従のみが解除され、GPS電源は維持。GPSがOFFの状態で `[◎]` を押すと連動して測位も起動。

### 2. 真北（True North）補正＆EXIF直書き撮影
- **真北補正:** 加速度センサー＋地磁気センサーの方位角に、`GeomagneticField` から算出した現地の地磁気偏角を加算補正。
- **EXIF直書き:** 撮影したJPEGのEXIFに測位座標と真方位（`TAG_GPS_IMG_DIRECTION_REF = "T"`）を直接書き込み。
- **撮影アシスト:** 画面中央の精密十字レティクルに加え、カメラモード中のみ進行・撮影方位を示す照射コーンを表示。

### 3. 超高速オフライン地図タイルキャッシュ
- **高速I/O:** Storage Access Framework（SAF）のオーバーヘッドを回避し、`getExternalCacheDirs` 経由の直接ファイルI/Oでタイルを高速読み書き。
- **ストレージ切替:** 内部ストレージとSDカード領域をワンタップで切替可能。
- **周辺事前ダウンロード:** 地図中心から「半径3km圏内タイル」をサーバー負荷に配慮したウェイト（約30ms）付きで一括キャッシュ。
- **圏外フォールバック:** 180日以内のキャッシュを即返却し、期限切れタイルでも圏外時はそのまま表示を維持。

### 4. 安全なライフサイクル設計
- **タスクキル連動:** アプリ履歴からのスワイプ終了（`onTaskRemoved`）を検知し、通知の消去とGPSチップのスタンバイ復帰を確実に実行。
- **誤終了防止:** GPS稼働中のAndroid戻るボタン（◁）操作ではタスクをバックグラウンドへ退避。
- **完全終了メニュー:** メニュー内の `[🚪 アプリを完全に終了]` からプロセスごと即座に安全破棄。

---

## 🛠 技術スタック

| 項目 | バージョン / ライブラリ |
| :--- | :--- |
| **OS / SDK** | minSdk 26 / compileSdk 34 / targetSdk 34 |
| **言語 / ビルド** | Kotlin 1.9.22 / AGP 8.2.2 / Gradle 8.5 / Java 17 |
| **カメラ** | CameraX 1.4.1 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) |
| **地図・GIS** | Leaflet.js 1.9.4 / Semicircle.js / Android WebView |
| **位置情報・センサー** | Google Play Services Location (`FusedLocationProviderClient`) / Android Sensor Framework / `GnssStatus` |
| **EXIF処理** | AndroidX ExifInterface 1.3.7 |

---

## 📦 ビルド方法

### GitHub Actions（自動ビルド）
リポジトリの `main` ブランチにプッシュすると自動でワークフローが起動し、`Actions` タブの各ビルド詳細ページ（Artifacts）からインストール用APK（`app-debug.apk`）をダウンロードできます。

### ローカル環境
```bash
./gradlew assembleDebug
