[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$ToolRoot,
    [switch]$BuildOnly,
    [switch]$Clear
)
$ErrorActionPreference = 'Stop'
$toolDirectory = (Resolve-Path -LiteralPath $ToolRoot).Path
$project = Join-Path $toolDirectory 'notification-demo'
$adb = Join-Path $toolDirectory 'android/platform-tools/adb.exe'
$gradle = Join-Path $toolDirectory 'gradle-9.6.0/bin/gradle.bat'
$jdk = Get-ChildItem -LiteralPath (Join-Path $toolDirectory 'jdk') -Directory | Select-Object -First 1
if ($null -eq $jdk -or -not (Test-Path -LiteralPath $gradle) -or -not (Test-Path -LiteralPath $adb)) { throw 'ToolRoot must contain jdk, gradle-9.6.0 and android/platform-tools.' }
$fixtures = @(
    @{Name='messages'; Label='Demo · Messages'; Category='social'; Icon='ic_dialog_email'},
    @{Name='work'; Label='Demo · Work'; Category='productivity'; Icon='ic_menu_agenda'},
    @{Name='journey'; Label='Demo · Journey'; Category='maps'; Icon='ic_dialog_map'}
)
# The literal single quotes travel through adb to Android's shell and protect the nested class dollar sign.
if ($Clear) {
    foreach ($fixture in $fixtures) {
        & $adb -e shell am broadcast --include-stopped-packages -n "'dev.glgra.launcher.demo.$($fixture.Name)/dev.glgra.launcher.demo.NotificationDemo`$Receiver'" -a dev.glgra.launcher.demo.CLEAR
        if ($LASTEXITCODE -ne 0) { throw 'Could not clear a companion app. Check that it is installed on the emulator.' }
    }
    return
}
function Write-Generated([string]$Path, [string]$Text) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}
[IO.Directory]::CreateDirectory($project) | Out-Null
Write-Generated (Join-Path $project 'settings.gradle') @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = 'LauncherNotificationDemo'
include ':messages', ':work', ':journey'
'@
Write-Generated (Join-Path $project 'build.gradle') "plugins { id 'com.android.application' version '9.4.0' apply false }`n"
Write-Generated (Join-Path $project 'gradle.properties') "android.builtInKotlin=false`norg.gradle.jvmargs=-Xmx1536m`n"
$template = Join-Path (Split-Path -Parent $PSScriptRoot) 'demo/NotificationDemo.java'
foreach ($fixture in $fixtures) {
    $module = Join-Path $project $fixture.Name
    $id = "dev.glgra.launcher.demo.$($fixture.Name)"
    $build = @"
plugins { id 'com.android.application' }
android {
    namespace 'dev.glgra.launcher.demo'
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion '37.0.0'
    defaultConfig { applicationId '$id'; minSdk 34; targetSdk 37; versionCode 1; versionName '1.0-local-demo' }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
    signingConfigs { debug { storeFile rootProject.file('demo-debug.p12'); storePassword 'android'; keyAlias 'androiddebugkey'; keyPassword 'android' } }
}
"@
    Write-Generated (Join-Path $module 'build.gradle') $build
    $manifest = @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <application android:label="$($fixture.Label)" android:icon="@android:drawable/$($fixture.Icon)" android:appCategory="$($fixture.Category)" android:allowBackup="false" android:theme="@android:style/Theme.Material.Light.NoActionBar">
    <activity android:name="dev.glgra.launcher.demo.NotificationDemo" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
    <receiver android:name="dev.glgra.launcher.demo.NotificationDemo`$Receiver" android:exported="true" />
    <service android:name="dev.glgra.launcher.demo.NotificationDemo`$CallExampleService" android:exported="true" android:foregroundServiceType="shortService" />
  </application>
</manifest>
"@
    Write-Generated (Join-Path $module 'src/main/AndroidManifest.xml') $manifest
    $java = Join-Path $module 'src/main/java/dev/glgra/launcher/demo/NotificationDemo.java'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $java)) | Out-Null
    Copy-Item -LiteralPath $template -Destination $java -Force
}
$names = @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME')
$old = @{}
foreach ($name in $names) { $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $jdk.FullName
    $env:ANDROID_HOME = Join-Path $toolDirectory 'android'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:GRADLE_USER_HOME = Join-Path $toolDirectory 'gradle-home'
    $key = Join-Path $project 'demo-debug.p12'
    if (-not (Test-Path -LiteralPath $key)) {
        # Throwaway Android debug identity only. This is unrelated to the launcher's private release identity.
        & (Join-Path $jdk.FullName 'bin/keytool.exe') -genkeypair -keystore $key -storetype PKCS12 -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Android Local Demo,O=Local,C=IN'
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the throwaway companion debug key.' }
    }
    & $gradle -p $project --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Companion apps did not build.' }
    foreach ($fixture in $fixtures) {
        $apk = Join-Path $project "$($fixture.Name)/build/outputs/apk/debug/$($fixture.Name)-debug.apk"
        if (-not (Test-Path -LiteralPath $apk)) { throw "Missing generated APK: $apk" }
        if ($BuildOnly) { Write-Output $apk; continue }
        & $adb -e install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'Could not install a companion app on the emulator.' }
        $id = "dev.glgra.launcher.demo.$($fixture.Name)"
        & $adb -e shell pm grant $id android.permission.POST_NOTIFICATIONS
        if ($LASTEXITCODE -ne 0) { throw 'Could not authorize example notifications on the emulator.' }
        & $adb -e shell am broadcast --include-stopped-packages -n "'$id/dev.glgra.launcher.demo.NotificationDemo`$Receiver'" -a dev.glgra.launcher.demo.POST
        if ($LASTEXITCODE -ne 0) { throw 'Could not post the companion notifications.' }
        # Shell starts the test service explicitly; ordinary broadcasts never attempt a background FGS start.
        & $adb -e shell am start-foreground-service -n "'$id/dev.glgra.launcher.demo.NotificationDemo`$CallExampleService'"
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the temporary non-dismissible example.' }
    }
    if (-not $BuildOnly) { Write-Output 'Posted local examples from three clearly labelled demo apps. Call-style examples stop after two minutes; each demo app has Start/Stop controls. Its other controls repost examples, restart progress or clear its own notifications.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $old[$name], 'Process') }
}
