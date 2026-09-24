# 3D Engine Full Simulator (Android)

内燃機関・外燃機関・ロータリー・ガスタービン・電動機の **運動機構 / 熱力学 / 物理音響** をリアルタイムに再現する Android アプリ。
3D モデルは直接触らず、計器盤風の **操作コンソール** だけで運転とカメラを操作する。

- 仕様書: [docs/SPEC.md](docs/SPEC.md)
- 収録: 43 機種 (直列 1〜8 / V2〜V16 / 水平対向 / 星型 3〜28 気筒 / ヴァンケル 1〜4 ローター / デルティック・Jumo 205 / 蒸気機関 / スターリング / ターボジェット・ファン・シャフト / PMSM・誘導モータ)
- 機種は `app/src/main/assets/engines/*.json` を追加するだけで増やせる (スキーマは仕様書 §6)

## ビルド

```sh
# Android SDK (platform 36, build-tools 36, NDK 29.0.14206865, CMake 3.31.6) が必要
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # デバッグ鍵で署名したリリースビルド
```

GitHub Actions (`.github/workflows/android.yml`) でも APK が成果物として生成される。

## テスト

```sh
# ネイティブコア (キネマティクス/熱力学/音響) をPC上で検証
cmake -S tests -B tests/build -G Ninja && cmake --build tests/build && ./tests/build/host_tests
./tests/build/host_tests --wav            # 各機種の音を WAV 出力 (出力先はソース内のパス)

# 実機と同じ GLES3 レンダラを Mesa (EGL サーフェスレス) で描画してスクリーンショット
./tests/build/render_shots <outdir> v8_cross 0 0 2 1   # <mode preset>... (mode: 0 solid 1 xray 2 section 3 thermal 4 stress)

# コンソール UI のレイアウト (Robolectric, ネイティブ不要) → app/build/screenshots/
./gradlew testDebugUnitTest
```

## 構成

| パス | 内容 |
|---|---|
| `app/src/main/cpp/core` | JSON パーサ、エンジン定義 (レイアウト展開) |
| `app/src/main/cpp/sim` | 厳密キネマティクス、熱力学・動力学シミュレーション |
| `app/src/main/cpp/audio` | 物理音響合成 DSP、Oboe 出力 |
| `app/src/main/cpp/render` | 手続き的メッシュ、シーン、PBR/X線/断面/ヒートマップ描画、カメラ |
| `app/src/main/cpp/jni` | Kotlin との境界 |
| `app/src/main/java/.../` | 操作コンソール UI、テレメトリ、GL ビュー |
| `tests/` | ホストテスト、ヘッドレス描画 |
