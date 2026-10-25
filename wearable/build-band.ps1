param([string]$JavaPath = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$vanBandRoot = Split-Path $PSScriptRoot -Parent
if (!$JavaPath) { $JavaPath = 'C:\Users\ASUS\AppData\Local\Programs\Microsoft\jdk-21.0.9.10-hotspot' }
if (!(Test-Path -LiteralPath (Join-Path $vanBandRoot 'signing.properties'))) { throw '需要手机端同一份本机 signing.properties；私钥不在源码交付中。' }
$env:VAN_BAND_JAVA = Join-Path $JavaPath 'bin\java.exe'
$vanBandCli = Join-Path $PSScriptRoot 'node_modules\aiot-toolkit\lib\bin.js'
if (!(Test-Path -LiteralPath $vanBandCli)) { $vanBandCli = Join-Path $vanBandRoot '.tools\vanband\toolchain\node_modules\aiot-toolkit\lib\bin.js' }
if (!(Test-Path -LiteralPath $vanBandCli)) { throw '请先在 wearable 目录执行 npm install。' }
Push-Location $PSScriptRoot
try {
    $vanBandStarted = Get-Date
    & node -r (Join-Path $PSScriptRoot 'scripts\signer-preload.cjs') $vanBandCli build | Tee-Object -Variable vanBandOutput
    if ($LASTEXITCODE -ne 0 -or ($vanBandOutput -join "`n") -notmatch 'build success:' -or ($vanBandOutput -join "`n") -match 'build error') { throw 'RPK 构建失败' }
    $vanBandManifest = Get-Content (Join-Path $PSScriptRoot 'src\manifest.json') -Raw | ConvertFrom-Json
    $vanBandRpk = Join-Path $PSScriptRoot "dist\$($vanBandManifest.package).debug.$($vanBandManifest.versionName).rpk"
    if (!(Test-Path -LiteralPath $vanBandRpk) -or (Get-Item -LiteralPath $vanBandRpk).LastWriteTime -lt $vanBandStarted.AddSeconds(-1)) { throw '未生成新的 RPK，拒绝交付旧产物' }
}
finally { Pop-Location }
