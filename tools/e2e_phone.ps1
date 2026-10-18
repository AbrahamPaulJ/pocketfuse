# Runs the on-phone end-to-end suite (ServerE2ETest) against a real FaceFusion server.
# Usage: tools\e2e_phone.ps1 -Serial <adb serial> -Pairing 'pocketfuse://pair?host=..&port=..&key=..' [-Adb path] [-Class name]
# The pairing link is what `python pocketfuse.py serve` prints. It is passed to the phone, never logged here.
param(
	[Parameter(Mandatory = $true)] [string] $Serial,
	[Parameter(Mandatory = $true)] [string] $Pairing,
	[string] $Adb = 'adb',
	[string] $Class = 'io.github.abrahampaulj.pocketfuse.ServerE2ETest'
)
$root = Join-Path $PSScriptRoot '..\android\app\build\outputs\apk'
& (Join-Path $PSScriptRoot 'build_android.ps1') assembleDebug assembleDebugAndroidTest | Select-String 'GRADLE_EXIT'
& $Adb -s $Serial install -r (Join-Path $root 'debug\app-debug.apk') | Out-Null
& $Adb -s $Serial install -r -t (Join-Path $root 'androidTest\debug\app-debug-androidTest.apk') | Out-Null
$sw = [Diagnostics.Stopwatch]::StartNew()
& $Adb -s $Serial shell am instrument -w -r -e pairing "'$Pairing'" -e class $Class io.github.abrahampaulj.pocketfuse.test/androidx.test.runner.AndroidJUnitRunner |
	Select-String -Pattern 'INSTRUMENTATION_STATUS: test=|INSTRUMENTATION_STATUS_CODE: -2|FAILURES|OK \(|Tests run|AssertionError|Exception' |
	ForEach-Object { $_.Line }
"E2E_TIME=$([math]::Round($sw.Elapsed.TotalSeconds))s"
