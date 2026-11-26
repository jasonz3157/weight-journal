# 体重日记

使用 Jetpack Compose 和 COUI Kit 构建的 Android 体重记录应用。目标系统为 Android 17（API 37），最低支持 Android 12（API 31）。

- **记录**页每天保存一条体重，支持修改。设置身高后可查看 BMI 和 BMI 趋势。
- **历史**页可按日期倒序查看体重、相较上一条记录的变化和 BMI，也可切换月历，点击日期补记或修改。
- **设置**页用于填写身高及 WebDAV 配置。应用主题随系统切换，桌面图标提供日间、夜间和单色资源。

## 构建

使用 JDK 25 和 Android SDK Platform 37.2、Build Tools 37.0.0。设置 `ANDROID_HOME`，或在 `local.properties` 中指定 `sdk.dir`。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 同步

在 App 和兼容的客户端中填入同一 HTTPS WebDAV 目录或 JSON 文件地址。使用目录地址时，读写 `weight-journal.json`。文件保存体重、身高和删除标记，各客户端按条目合并。App 直连配置的 WebDAV 服务。普通服务使用强 ETag 和条件写入，坚果云可使用文件锁或 ETag 保护合并写入。

App 在 Android Keystore 保护下保存 WebDAV 凭据。配置后可立即同步，启动、回到前台、修改记录及前台定时检查也会触发同步。
