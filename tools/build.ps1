param([switch]$Offline, [switch]$UseExistingCache, [switch]$Release, [string]$JavaHome, [switch]$OfflineOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
    throw '请设置 JAVA_HOME 为 JDK 17 或 21，或传入 -JavaHome。'
}
Push-Location -LiteralPath $projectRoot
try {
    if ($UseExistingCache) { python tools/cache_repository.py; if ($LASTEXITCODE) { throw '缓存映射失败' } }
    # Gradle worker classpaths can fail on non-ASCII Windows paths. Keep the same checkout.
    if ($projectRoot -match '[^\x00-\x7F]') {
        $hasher = [Security.Cryptography.SHA256]::Create()
        $hash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectRoot))).Replace('-', '').Substring(0,10)
        $hasher.Dispose()
        $aliasPath = Join-Path $env:TEMP ('clearscreen-' + $hash)
        if (Test-Path -LiteralPath $aliasPath) {
            $existing = Get-Item -LiteralPath $aliasPath
            if ($existing.LinkType -ne 'Junction' -or $existing.Target -ne $projectRoot) { throw '临时构建别名已被其他路径占用，未更改。' }
        } else { New-Item -ItemType Junction -Path $aliasPath -Target $projectRoot | Out-Null }
        Set-Location -LiteralPath $aliasPath
    }
    $variant = if ($Release) { 'release' } else { 'debug' }
    if ($Release -and -not (Test-Path -LiteralPath '.local/signing.properties')) { throw '发布构建需要本地签名配置，详见 README。' }
    $assemble = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    $buildArgs = @('--no-daemon',':app:testDebugUnitTest',':app:lintDebug',$assemble,'--console=plain')
    if ($Offline) { $buildArgs += '--offline' }
    $buildArgs += if ($OfflineOnly) { '-PofflineOnly=true' } else { '-PofflineOnly=false' }
    & .\gradlew.bat @buildArgs
    if ($LASTEXITCODE) { throw '构建或检查失败；未更新交付 APK。' }
    $versionMatch = [regex]::Match((Get-Content -LiteralPath (Join-Path $projectRoot 'app/build.gradle') -Raw), "versionName '([^']+)'")
    if (-not $versionMatch.Success) { throw '无法读取应用版本；未更新交付 APK。' }
    $versionName = $versionMatch.Groups[1].Value
    $flavorSuffix = if ($OfflineOnly) { '-offline' } else { '' }
    $variantSuffix = if ($Release) { '' } else { '-debug' }
    $name = 'ClearScreen-' + $versionName + $flavorSuffix + $variantSuffix + '.apk'
    $apk = Join-Path $projectRoot ('dist/' + $name)
    $edition = if ($OfflineOnly) { 'offline' } else { 'full' }
    New-Item -ItemType Directory -Path (Join-Path $projectRoot 'dist') -Force | Out-Null
    $stagedApk = Join-Path 'dist' ('.clearscreen-candidate-' + [Guid]::NewGuid().ToString('N') + '.apk')
    try {
        Copy-Item -LiteralPath ('app/build/outputs/apk/'+$variant+'/app-'+$variant+'.apk') -Destination $stagedApk
        python tools/verify_apk.py $stagedApk --variant $edition --build-type $variant
        if ($LASTEXITCODE) { throw '安装包校验失败；未更新交付 APK。' }
        Move-Item -LiteralPath $stagedApk -Destination $apk -Force
    } finally {
        if (Test-Path -LiteralPath $stagedApk) { Remove-Item -LiteralPath $stagedApk }
    }
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally { Pop-Location }
