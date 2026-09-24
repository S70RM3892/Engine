# 3D Engine Simulator / ENGINE EMPIRE v0.3.0

## 2 つのアプリ

| APK | アプリ名 | パッケージ | 内容 |
|---|---|---|---|
| `EngineSimulator-*.apk` | **3D Engine Simulator** | `com.s70rm3892.enginesim` | エンジン・フルシミュレーター (43 機種 + n 気筒設計、台上/MT/AT、アクセルペダル、Vulkan/GLES 描画、物理音響) |
| `EngineEmpire-*.apk` | **ENGINE EMPIRE** | `com.s70rm3892.engineempire` | 同じ物理エンジンで動くインクリメンタルゲーム (実出力 − 燃料代 = 収入、13 台のエンジン、チューン、ゼロヨン、プレステージ) |

パッケージ名が別なので両方同時にインストールでき、セーブデータも独立している。

- 対応: Android 8.0 (API 26) 以上、arm64-v8a / x86_64
- 描画: Vulkan 対応端末は Vulkan、非対応端末は OpenGL ES 3.0 に自動切替
- 既知の制約: 実機での動作確認は未実施 (ホスト上のテスト・ソフトウェア Vulkan・Robolectric で検証)。詳細はソース内の `docs/SPEC.md`

インストール: APK をダウンロード → 「提供元不明のアプリ」を許可してインストール。
