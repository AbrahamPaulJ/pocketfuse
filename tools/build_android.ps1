# Builds the Android app. Usage: tools\build_android.ps1 [gradle tasks...]  (default: assembleDebug)
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Tasks)
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
if (-not $Tasks) { $Tasks = @('assembleDebug') }
$root = Join-Path $PSScriptRoot '..\android'
Push-Location $root
try {
	$sw = [Diagnostics.Stopwatch]::StartNew()
	& .\gradlew.bat @Tasks --console=plain
	$code = $LASTEXITCODE
	"GRADLE_EXIT=$code TIME=$([math]::Round($sw.Elapsed.TotalSeconds))s"
	exit $code
} finally {
	Pop-Location
}
