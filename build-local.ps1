param(
    [string[]]$Tasks = @(':core:domain:test', ':app:assembleDebug', ':app:lintDebug'),
    [string]$SdkPath = '',
    [string]$JavaPath = ''
)
$ErrorActionPreference = 'Stop'
$projectPath = [IO.Path]::GetFullPath($PSScriptRoot)
$toolPath = $null
$searchDirectory = [IO.DirectoryInfo]::new($projectPath)
while ($searchDirectory) {
    $candidate = Join-Path $searchDirectory.FullName '.tools'
    if (Test-Path -LiteralPath (Join-Path $candidate 'android-sdk\platform-tools')) {
        $toolPath = $candidate
        break
    }
    $searchDirectory = $searchDirectory.Parent
}
if (!$SdkPath) {
    if ($toolPath) {
        $SdkPath = Join-Path $toolPath 'sdk'
        if (!(Test-Path -LiteralPath $SdkPath)) { $SdkPath = Join-Path $toolPath 'android-sdk' }
    } elseif ($env:ANDROID_HOME) {
        $SdkPath = $env:ANDROID_HOME
    } elseif ($env:ANDROID_SDK_ROOT) {
        $SdkPath = $env:ANDROID_SDK_ROOT
    }
}
if (!$SdkPath -or !(Test-Path -LiteralPath (Join-Path $SdkPath 'platform-tools'))) {
    throw '没有找到 Android SDK：工作区内应有 .tools/android-sdk，其他环境请设置 ANDROID_HOME 或用 -SdkPath 指定。'
}
if (!$JavaPath) { $JavaPath = $env:JAVA_HOME }
if (!$JavaPath) {
    $vanJavaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($vanJavaCommand) { $JavaPath = Split-Path (Split-Path $vanJavaCommand.Source -Parent) -Parent }
}
if (!$JavaPath -or !(Test-Path -LiteralPath (Join-Path $JavaPath 'bin\java.exe'))) {
    throw '没有可用的 JDK：请设置 JAVA_HOME，或用 -JavaPath 指定（需要 JDK 21）。'
}
$hash = [Security.Cryptography.SHA256]::Create()
try { $key = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectPath)))).Replace('-', '').Substring(0, 12).ToLowerInvariant() }
finally { $hash.Dispose() }
function Get-AsciiAlias([string]$Name, [string]$Target) {
    $resolvedTarget = (Resolve-Path -LiteralPath $Target).Path
    $aliasPath = Join-Path ([IO.Path]::GetTempPath()) "van-$key-$Name"
    if (Test-Path -LiteralPath $aliasPath) {
        $existing = Get-Item -LiteralPath $aliasPath
        $existingTarget = @($existing.Target)[0]
        if (!$existingTarget -or [IO.Path]::GetFullPath($existingTarget) -ne $resolvedTarget) { throw "路径别名已被其他文件占用：$aliasPath" }
    } else { New-Item -ItemType Junction -Path $aliasPath -Target $resolvedTarget | Out-Null }
    return $aliasPath
}
$env:JAVA_HOME = $JavaPath
$propertyFile = Join-Path $projectPath 'local.properties'

if ($toolPath) {
    New-Item -ItemType Directory -Path (Join-Path $toolPath 'gradle-home') -Force | Out-Null
    $projectAlias = Get-AsciiAlias 'project' $projectPath
    $sdkAlias = Get-AsciiAlias 'sdk' $SdkPath
    $cacheAlias = Get-AsciiAlias 'gradle' (Join-Path $toolPath 'gradle-home')
    $properties = if (Test-Path -LiteralPath $propertyFile) { [IO.File]::ReadAllText($propertyFile) } else { '' }
    $sdkProperty = 'sdk.dir=' + $sdkAlias.Replace('\', '/').Replace(':', '\:')
    if ($properties -match '(?m)^sdk\.dir=.*$') { $properties = [regex]::Replace($properties, '(?m)^sdk\.dir=.*$', { $sdkProperty }) }
    else { $properties = $properties.TrimEnd() + [Environment]::NewLine + $sdkProperty + [Environment]::NewLine }
    [IO.File]::WriteAllText($propertyFile, $properties)
    $gradleCommand = Join-Path $toolPath 'gradle-9.3.1\bin\gradle.bat'
    if (!(Test-Path -LiteralPath $gradleCommand)) { $gradleCommand = Join-Path $projectPath 'gradlew.bat' }
    & $gradleCommand "--project-dir=$projectAlias" "--gradle-user-home=$cacheAlias" @Tasks --no-daemon
} else {
    # 没有共享工具目录时退回 wrapper + 环境变量里的 SDK，不再依赖工作区外的 .tools。
    Write-Host "未找到 .tools/android-sdk，改用 gradlew 与 ANDROID_HOME（SDK: $SdkPath）" -ForegroundColor Yellow
    $properties = if (Test-Path -LiteralPath $propertyFile) { [IO.File]::ReadAllText($propertyFile) } else { '' }
    $sdkProperty = 'sdk.dir=' + $SdkPath.Replace('\', '/').Replace(':', '\:')
    if ($properties -match '(?m)^sdk\.dir=.*$') { $properties = [regex]::Replace($properties, '(?m)^sdk\.dir=.*$', { $sdkProperty }) }
    else { $properties = $properties.TrimEnd() + [Environment]::NewLine + $sdkProperty + [Environment]::NewLine }
    [IO.File]::WriteAllText($propertyFile, $properties)
    & (Join-Path $projectPath 'gradlew.bat') @Tasks --no-daemon
}
if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
