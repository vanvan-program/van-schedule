<#
.SYNOPSIS
    van课表 发版脚本：同步版本号、构建、生成校验文件、归档产物、打本地 tag。

.DESCRIPTION
    替代原来手工改 app/build.gradle、手工算 SHA-256、手工打 tag 的流程。
    脚本只做机械同步，不生成发布说明：发版前必须先把 changelog.txt 与 README.md
    的首节标题写成「## <版本> 标题」，否则脚本拒绝继续。

.PARAMETER Version
    要发布的版本号，形如 0.5.21。versionCode 由次版本与修订号推出：minor*10+patch。

.PARAMETER SkipBuild
    跳过构建，直接用 app/build/outputs/apk/release 下已有的 APK（仅用于重跑归档与打 tag）。

.PARAMETER NoTag
    不创建本地 tag。

.PARAMETER AllowDirty
    允许工作区有未提交改动（默认不允许，防止把开发中的代码打进发布包）。

.EXAMPLE
    .\tools\release.ps1 -Version 0.5.21
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$SkipBuild,
    [switch]$NoTag,
    [switch]$AllowDirty
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path $PSScriptRoot -Parent
if (!(Test-Path (Join-Path $repo 'app\build.gradle'))) {
    throw "未能在 $repo 找到 app/build.gradle，请把脚本放在仓库的 tools 目录下"
}
Set-Location $repo

function Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail([string]$text) { throw $text }

# 1. 版本号格式与 versionCode 推导
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)$') { Fail "版本号格式应为 X.Y.Z，收到：$Version" }
$major = [int]$Matches[1]; $minor = [int]$Matches[2]; $patch = [int]$Matches[3]
$newCode = $minor * 10 + $patch

$gradleFile = Join-Path $repo 'app\build.gradle'
$gradle = [IO.File]::ReadAllText($gradleFile)
$currentName = [regex]::Match($gradle, "versionName\s+'([^']+)'").Groups[1].Value
$currentCodeText = [regex]::Match($gradle, 'versionCode\s+(\d+)').Groups[1].Value
if (!$currentName -or !$currentCodeText) { Fail 'app/build.gradle 里没有找到 versionName / versionCode' }
$currentCode = [int]$currentCodeText

Step "当前版本 $currentName ($currentCode) → 目标 $Version ($newCode)"
if ($newCode -le $currentCode) {
    Fail "目标 versionCode $newCode 不大于当前 $currentCode。版本号写错了，或者忘了先升版本。"
}

# 2. tag 不能已存在
if (!$NoTag) {
    $existing = git tag -l "v$Version"
    if ($existing) { Fail "tag v$Version 已存在。要重发请先 git tag -d v$Version，或用 -NoTag。" }
}

# 3. 工作区必须干净
if (!$AllowDirty) {
    $dirty = git status --porcelain
    if ($dirty) { Fail "工作区有未提交改动，先提交或用 -AllowDirty：`n$dirty" }
}

# 4. 发布说明先写好，脚本不代写
$changelog = Get-Content (Join-Path $repo 'app\src\main\assets\changelog.txt') -TotalCount 1
if ($changelog -notmatch "^##\s+$([regex]::Escape($Version))\s+\S") {
    Fail "app/src/main/assets/changelog.txt 首行不是「## $Version 标题」。先写好本版说明再来发版。"
}
$readmeHead = Select-String -Path (Join-Path $repo 'README.md') -Pattern "^##\s+$([regex]::Escape($Version))\s+\S" -List
if (!$readmeHead) {
    Fail "README.md 里没有「## $Version 标题」这一节。先写好本版说明再来发版。"
}

# 5. 写回版本号（只改这两行，保持原有缩进与写法）
Step '更新 app/build.gradle 的 versionCode / versionName'
$upgraded = $gradle -replace 'versionCode\s+\d+', "versionCode $newCode"
$upgraded = $upgraded -replace "versionName\s+'[^']+'", "versionName '$Version'"
if ($upgraded -notmatch "versionName '$([regex]::Escape($Version))'") { Fail 'versionName 写回失败，请检查 app/build.gradle 的写法' }
[IO.File]::WriteAllText($gradleFile, $upgraded)

# 6. 构建
$apkDir = Join-Path $repo 'app\build\outputs\apk\release'
if (!$SkipBuild) {
    Step '构建 release APK'
    & (Join-Path $repo 'build-local.ps1') -Tasks ':app:assembleRelease'
    if ($LASTEXITCODE -ne 0) { Fail "构建失败，退出码 $LASTEXITCODE。版本号已改在 app/build.gradle，需要回滚请 git checkout -- app/build.gradle" }
}

# 7. 找 APK 并按发布约定重命名
$apk = Get-ChildItem $apkDir -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$apk) { Fail "在 $apkDir 找不到 APK。构建没成功，或用 -SkipBuild 时产物不存在。" }

$releaseDir = Join-Path $repo "deliverables\release-$Version"
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

$apkName = "van-schedule-$Version-arm64.apk"
$apkTarget = Join-Path $releaseDir $apkName
Copy-Item $apk.FullName $apkTarget -Force
Step "APK 已归档：deliverables\release-$Version\$apkName"

# 8. 源码包
$sourceName = "van-schedule-$Version-source.zip"
$sourceTarget = Join-Path $releaseDir $sourceName
Step "打包源码 $sourceName"
$tempSource = Join-Path ([IO.Path]::GetTempPath()) "van-source-$Version"
if (Test-Path $tempSource) { Remove-Item $tempSource -Recurse -Force }
git archive --format=zip --prefix="van-schedule-$Version/" -o $sourceTarget HEAD
if ($LASTEXITCODE -ne 0) { Fail 'git archive 失败，源码包没有生成' }

# 9. 校验文件
Step '生成 SHA256SUMS'
$sumFile = Join-Path $releaseDir "SHA256SUMS-$Version.txt"
$lines = @()
foreach ($f in @($apkTarget, $sourceTarget)) {
    $hash = (Get-FileHash $f -Algorithm SHA256).Hash.ToLowerInvariant()
    $lines += "$hash  $([IO.Path]::GetFileName($f))"
}
[IO.File]::WriteAllLines($sumFile, $lines, [Text.UTF8Encoding]::new($false))
Get-Content $sumFile | ForEach-Object { Write-Host "    $_" }

# 10. 打 tag
if (!$NoTag) {
    Step "打 tag v$Version"
    git tag "v$Version"
    if ($LASTEXITCODE -ne 0) { Fail "git tag v$Version 失败" }
}

Step '完成。还需要手工做的：'
Write-Host @"
    1. 提交版本号改动：git add app/build.gradle && git commit -m "Release $Version"
    2. tag 指向的应是发布提交，若上面的 commit 在打 tag 之后，删掉 tag 重打：
       git tag -d v$Version && git tag v$Version
    3. 推送：git push origin main && git push origin v$Version
    4. 挂 Release：gh release create v$Version --latest --notes-file <发布说明>
       deliverables/release-$Version/ 下三个资源：APK、源码 ZIP、SHA256SUMS
"@
