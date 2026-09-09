param([switch]$InstallSdk)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $projectRoot '.build-tools/android-sdk'
$javaRoot = (Get-ChildItem (Join-Path $projectRoot '.build-tools/jdk21') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin/jlink.exe') } | Select-Object -First 1).FullName
if (!$javaRoot) { throw 'A complete JDK 21 with jlink is required in .build-tools/jdk21' }
$flutterRoot = 'C:/Users/LOQ/flutter'
$env:JAVA_HOME = $javaRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:PATH = "$javaRoot/bin;$env:PATH"
Set-Location $projectRoot
if ($InstallSdk) {
    # Call the native CLI directly: the compatibility .bat wrapper splits SDK
    # package names containing semicolons on this Windows host.
    & "$sdkRoot/cmdline-tools/bin/android.exe" --no-metrics --sdk $sdkRoot sdk install 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0' 'ndk;28.2.13676358'
    if ($LASTEXITCODE -ne 0) { throw 'SDK install failed' }
}
# Flutter's Gradle plugin compiles the Dart release assets as part of assemble.
# Direct Gradle also preserves the escaped Windows paths in local.properties.
& ./android/gradlew.bat -p ./android assembleRelease testReleaseUnitTest lintRelease '-Ptarget-platform=android-arm,android-arm64,android-x64'
if ($LASTEXITCODE -ne 0) { throw 'Release build or Android checks failed' }
