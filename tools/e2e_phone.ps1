# Runs the on-phone end-to-end suite (ServerE2ETest) against a real FaceFusion server.
# Usage: tools\e2e_phone.ps1 -Serial <adb serial> -Pairing 'pocketfuse://pair?host=..&port=..&key=..' [-Adb path] [-Class name]
# The pairing link is what `python pocketfuse.py serve` prints. It is passed to the phone, never logged here.
param(
	[Parameter(Mandatory = $true)] [string] $Serial,
	[Parameter(Mandatory = $true)] [string] $Pairing,
	[string] $Adb = 'adb',
	[string] $Class = ''
)
# Empty runs every e2e class; a name ending in Test runs that class, anything else is a package.
$filter = if (-not $Class) { @() } elseif ($Class -match 'Test(#\w+)?$') { @('-e', 'class', $Class) } else { @('-e', 'package', $Class) }
$root = Join-Path $PSScriptRoot '..\android\app\build\outputs\apk'
& (Join-Path $PSScriptRoot 'build_android.ps1') assembleDebug assembleDebugAndroidTest | Select-String 'GRADLE_EXIT'
& $Adb -s $Serial install -r (Join-Path $root 'debug\app-debug.apk') | Out-Null
& $Adb -s $Serial install -r -t (Join-Path $root 'androidTest\debug\app-debug-androidTest.apk') | Out-Null
& $Adb -s $Serial logcat -c
$sw = [Diagnostics.Stopwatch]::StartNew()
$lines = & $Adb -s $Serial shell am instrument -w -r -e pairing "'$Pairing'" @filter io.github.abrahampaulj.pocketfuse.debug.test/androidx.test.runner.AndroidJUnitRunner |
	Select-String -Pattern 'INSTRUMENTATION_STATUS: test=|INSTRUMENTATION_STATUS_CODE: -2|FAILURES|OK \(|Tests run|AssertionError|Exception' |
	ForEach-Object { $_.Line }
$lines
# A network adb link can drop the shell stream while the run carries on: read the runner's own log until it finishes.
if (-not ($lines -match 'OK \(|FAILURES')) {
	'adb stream ended early, reading the TestRunner log'
	while (-not (& $Adb -s $Serial logcat -d -s TestRunner:I | Select-String 'run finished') -and $sw.Elapsed.TotalMinutes -lt 10) { Start-Sleep 5 }
	& $Adb -s $Serial logcat -d -s TestRunner:I | Select-String 'failed:|run finished' | ForEach-Object { $_.Line }
}
"E2E_TIME=$([math]::Round($sw.Elapsed.TotalSeconds))s"
