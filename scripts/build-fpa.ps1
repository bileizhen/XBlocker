param([switch]$DeviceTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$taskTemp = Join-Path ([System.IO.Path]::GetPathRoot($projectRoot)) 'tmp\XBlocker-fpa'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$asciiRoot = Join-Path ([System.IO.Path]::GetPathRoot($projectRoot)) 'tmp\XBlocker-fpa-workspace'
if ($projectRoot -match '[^\x00-\x7F]') {
    if (Test-Path $asciiRoot) {
        $junction = Get-Item -LiteralPath $asciiRoot
        if ($junction.LinkType -ne 'Junction' -or $junction.Target -ne $projectRoot) {
            throw "Temporary workspace path is occupied: $asciiRoot"
        }
    } else { New-Item -ItemType Junction -Path $asciiRoot -Target $projectRoot | Out-Null }
}
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
Push-Location $projectRoot
try {
    # Windows JDK 21's AF_UNIX loopback can fail with an 8.3 temp path.
    $env:TEMP = $taskTemp
    $env:TMP = $taskTemp
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djava.io.tmpdir=$taskTemp -Djdk.net.unixdomain.tmpdir=$taskTemp"
    $tasks = @(':core:test', ':app:testFpaDebugUnitTest', ':app:assembleFpaDebug', ':app:lintFpaDebug', '-PfpaCompat=true')
    if ($projectRoot -match '[^\x00-\x7F]') {
        # Java's Windows @argfile parser uses the native code page for Gradle worker
        # classpaths. Route only test classpaths through an ASCII junction.
        $initFile = Join-Path $taskTemp 'test-classpath.gradle'
        @'
allprojects {
    tasks.withType(Test).configureEach {
        doFirst {
            def actualRoot = rootProject.projectDir.absolutePath
            def asciiRoot = rootProject.findProperty('xblockerAsciiRoot')
            classpath = project.files(classpath.files.collect { file ->
                file.absolutePath.startsWith(actualRoot + File.separator)
                    ? new File(asciiRoot, file.absolutePath.substring(actualRoot.length() + 1)) : file
            })
        }
    }
}
'@ | Set-Content -LiteralPath $initFile -Encoding utf8
        $tasks += @('-I', $initFile, "-PxblockerAsciiRoot=$asciiRoot")
    }
    if ($DeviceTests) {
        $tasks += ':app:connectedFpaDebugAndroidTest'
        $tasks += '-Pandroid.testInstrumentationRunnerArguments.class=io.github.xblocker.FpaCompatibilityTest,io.github.xblocker.AndroidRuntimeTest,io.github.xblocker.XposedScopeTest'
    }
    & .\gradlew.bat @tasks
    if ($LASTEXITCODE -ne 0) { throw "FPA build failed ($LASTEXITCODE)" }
    $artifactDirectory = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
    $apk = Join-Path $projectRoot 'build/fpa-app/outputs/apk/fpa/debug/app-fpa-debug.apk'
    Copy-Item -LiteralPath $apk -Destination (Join-Path $artifactDirectory 'XBlocker-0.3.5-fpa.1-debug.apk') -Force
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
    Pop-Location
}
