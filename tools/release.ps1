<#
.SYNOPSIS
    从已提交的版本构建 APK、导出源码 ZIP、生成校验清单和本地标签。

.DESCRIPTION
    先同步 app/build.gradle、README.md 与应用内更新日志并提交。
    脚本从干净的 HEAD 构建，APK、源码 ZIP 和标签使用同一提交。

.PARAMETER Version
    app/build.gradle 中已经提交的版本号。

.PARAMETER SkipBuild
    复用本脚本为当前提交构建且摘要一致的 APK。

.PARAMETER NoTag
    仅构建和归档，不创建本地标签。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [switch]$SkipBuild,
    [switch]$NoTag
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$tag = "v$Version"

$commit = git -C $repo rev-parse --verify HEAD
if ($LASTEXITCODE -ne 0) { throw '无法获取当前提交。' }

function Assert-ReleaseSource {
    $head = git -C $repo rev-parse --verify HEAD
    if ($LASTEXITCODE -ne 0 -or $head -cne $commit) { throw '发版过程中 HEAD 已变更。' }
    $dirty = git -C $repo status --porcelain
    if ($LASTEXITCODE -ne 0) { throw '无法检查工作区。' }
    if ($dirty) { throw "请先提交版本号、发布说明和源码改动：$([Environment]::NewLine)$($dirty -join [Environment]::NewLine)" }
}

Assert-ReleaseSource
$tagCommit = git -C $repo rev-parse --verify --quiet "refs/tags/$tag^{commit}"
if ($tagCommit -and $tagCommit -cne $commit) { throw "$tag 已指向其他提交。" }

$gradle = Get-Content -LiteralPath (Join-Path $repo 'app/build.gradle') -Raw -Encoding utf8
$versionName = [regex]::Match($gradle, "(?m)^\s*versionName\s+'([^']+)'").Groups[1].Value
$versionCode = [regex]::Match($gradle, '(?m)^\s*versionCode\s+(\d+)').Groups[1].Value
if ($versionName -cne $Version -or !$versionCode) {
    throw "请先提交 app/build.gradle 中的 versionName '$Version' 和 versionCode。"
}

$heading = "^##\s+$([regex]::Escape($Version))\s+\S"
$changelog = Get-Content -LiteralPath (Join-Path $repo 'app/src/main/assets/changelog.txt') -TotalCount 1 -Encoding utf8
if ($changelog -notmatch $heading -or
    !(Select-String -LiteralPath (Join-Path $repo 'README.md') -Pattern $heading -List -Encoding utf8)) {
    throw "请先提交应用内更新日志与 README.md 的 $Version 版说明。"
}

$apkDir = Join-Path $repo 'app/build/outputs/apk/release'
$provenanceFile = Join-Path $apkDir 'van-release-provenance.json'
if (!$SkipBuild) {
    Write-Host "构建 $tag ($commit)"
    & (Join-Path $repo 'build-local.ps1') -Tasks ':app:assembleRelease'
    if ($LASTEXITCODE -ne 0) { throw "Release APK 构建失败，退出码 $LASTEXITCODE。" }
}
Assert-ReleaseSource

$metadataPath = Join-Path $apkDir 'output-metadata.json'
$metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding utf8 | ConvertFrom-Json
$apk = $metadata.elements | Select-Object -First 1
if ($metadata.applicationId -cne 'app.van.timetable' -or $metadata.variantName -cne 'release' -or
    $metadata.elements.Count -ne 1 -or $apk.outputFile -cne 'app-release.apk' -or
    $apk.versionName -cne $Version -or $apk.versionCode -ne [int]$versionCode) {
    throw "构建产物与 $tag 的包名、版本号或 Release 变体不一致。"
}

$apkPath = Join-Path $apkDir $apk.outputFile
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $abis = @($archive.Entries.FullName | ForEach-Object {
        if ($_ -match '^lib/([^/]+)/[^/]+\.so$') { $Matches[1] }
    } | Sort-Object -Unique)
    if ($abis.Count -ne 1 -or $abis[0] -cne 'arm64-v8a') { throw '发布 APK 必须仅包含 arm64-v8a 原生库。' }
} finally {
    $archive.Dispose()
}

if ($SkipBuild) {
    if (!(Test-Path -LiteralPath $provenanceFile -PathType Leaf)) {
        throw '找不到构建记录。请先正常执行一次发版脚本。'
    }
    $previous = Get-Content -LiteralPath $provenanceFile -Raw -Encoding utf8 | ConvertFrom-Json
    if ($previous.commit -cne $commit -or $previous.sha256 -cne $apkHash) {
        throw '已有 APK 的构建提交或摘要不符，请重新构建。'
    }
} else {
    @{commit = $commit; sha256 = $apkHash} | ConvertTo-Json -Compress |
        Set-Content -LiteralPath $provenanceFile -Encoding utf8
}

$apkName = "van-schedule-$Version-arm64.apk"
$sourceName = "van-schedule-$Version-source.zip"
$sumsName = "SHA256SUMS-$Version.txt"
$releaseRoot = Join-Path $repo 'deliverables'
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
$releaseDir = Join-Path $releaseRoot "release-$Version"
$staging = Join-Path $releaseRoot ('.release-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    Copy-Item -LiteralPath $apkPath -Destination (Join-Path $staging $apkName)
    $sourcePath = Join-Path $staging $sourceName
    git -C $repo archive --format=zip "--prefix=van-schedule-$Version/" -o $sourcePath $commit
    if ($LASTEXITCODE -ne 0) { throw "源码归档失败：$commit" }

    $sums = foreach ($name in @($apkName, $sourceName)) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $staging $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $name"
    }
    if (!$sums[0].StartsWith("$apkHash ")) { throw '归档 APK 与构建摘要不符。' }
    [IO.File]::WriteAllLines((Join-Path $staging $sumsName), $sums, [Text.UTF8Encoding]::new($false))
    Assert-ReleaseSource
    if (Test-Path -LiteralPath $releaseDir) {
        foreach ($name in @($apkName, $sourceName, $sumsName)) {
            $existing = Join-Path $releaseDir $name
            if (!(Test-Path -LiteralPath $existing -PathType Leaf) -or
                (Get-FileHash -LiteralPath $existing).Hash -cne (Get-FileHash -LiteralPath (Join-Path $staging $name)).Hash) {
                throw "已有归档内容不同：$existing"
            }
        }
    } else {
        [IO.Directory]::Move($staging, $releaseDir)
    }

    if (!$NoTag -and !$tagCommit) {
        git -C $repo tag -a $tag -m "van课表 $Version" $commit
        if ($LASTEXITCODE -ne 0) { throw "创建标签 $tag 失败。归档位于 $releaseDir。" }
    }
    Write-Host "归档完成：$releaseDir"
} finally {
    if (Test-Path -LiteralPath $staging -PathType Container) {
        $resolvedStaging = (Resolve-Path -LiteralPath $staging).ProviderPath
        $resolvedRoot = (Resolve-Path -LiteralPath $releaseRoot).ProviderPath
        if ((Split-Path -Parent $resolvedStaging) -ceq $resolvedRoot -and
            (Split-Path -Leaf $resolvedStaging) -match '^\.release-[0-9a-f]{32}$') {
            Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
        }
    }
}
