param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$taskTemp = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'XBlocker-java-tmp'
New-Item -ItemType Directory -Force $taskTemp | Out-Null
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    # Windows 8.3 TEMP paths can break Java 21 AF_UNIX socket connections.
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=$taskTemp"
    & "$PSScriptRoot\..\gradlew.bat" @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
} finally { $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }
