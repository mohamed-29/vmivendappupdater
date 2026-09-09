$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
$ideaRoot = 'C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4'
$java = "$ideaRoot/jbr/bin/java.exe"
$kotlinLib = "$ideaRoot/plugins/Kotlin/kotlinc/lib"
$testClasspath = "$projectRoot/.build-tools/junit-4.13.2.jar;$projectRoot/.build-tools/hamcrest-core-1.3.jar;$kotlinLib/kotlin-stdlib.jar"
$source = 'android/app/src/main/kotlin/com/example/vmivendappupdater'
$tests = 'android/app/src/test/kotlin/com/example/vmivendappupdater'
& $java -cp "$kotlinLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $testClasspath -d .build-tools/recovery-tests "$source/RootShell.kt" "$source/RootInstaller.kt" "$source/CrashDiagnostics.kt" "$tests/InstallRecoveryTest.kt" "$tests/CrashDiagnosticsTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Recovery test compilation failed' }
& $java -cp "$projectRoot/.build-tools/recovery-tests;$testClasspath" org.junit.runner.JUnitCore com.example.vmivendappupdater.InstallRecoveryTest com.example.vmivendappupdater.CrashDiagnosticsTest
if ($LASTEXITCODE -ne 0) { throw 'Recovery tests failed' }
