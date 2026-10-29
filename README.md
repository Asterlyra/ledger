# 日常账本

本地运行的 Android 账本，使用 Kotlin、Jetpack Compose 和 Room，支持收支、转账、订阅预期、耐用品折旧、账单导入以及 JSON 备份与恢复。最低 Android 8.0。

## 构建

使用 JDK 17 或 21 和 Android SDK 36，在 Android Studio 打开项目，或配置 `ANDROID_HOME` 后运行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

安卓设备或模拟器上的测试可使用 `./gradlew :app:connectedDebugAndroidTest`。应用数据保存在本机，备份是明文，可能包含账单及导入记录，请妥善保管。

## 许可

本项目原创代码采用 [MIT License](LICENSE)。第三方代码、数据和素材各自适用其原有许可。
