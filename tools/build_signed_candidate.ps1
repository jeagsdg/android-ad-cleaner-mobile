param(
    [Parameter(Mandatory)][string]$CandidateDirectory,
    [string]$SourceDirectory,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$source = if ($SourceDirectory) { (Resolve-Path -LiteralPath $SourceDirectory).Path } else { $project }
$destination = [IO.Path]::GetFullPath($CandidateDirectory)
if ((Test-Path -LiteralPath $destination) -or $destination.StartsWith($project + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use a new candidate directory outside the source project.'
}
if ($destination -match '[^\x00-\x7f]') { throw 'Use an ASCII path for the Windows Gradle worker.' }
$snapshot = Join-Path $destination 'source'
New-Item -ItemType Directory -Path $snapshot -Force | Out-Null
$inventory = & python (Join-Path $project 'tools/stage_release.py') --fingerprint --fingerprint-root $source
if ($LASTEXITCODE) { throw 'Could not fingerprint the build inputs.' }
$inventory | Set-Content -LiteralPath (Join-Path $destination 'build-source-hashes.json') -Encoding utf8
$inputs = ($inventory -join [Environment]::NewLine | ConvertFrom-Json -AsHashtable).Keys
foreach ($name in @($inputs)) {
    $target = Join-Path $snapshot $name
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath (Join-Path $source $name) -Destination $target
}
foreach ($name in @('tools/stage_release.py','tools/verify_apk.py','local.properties')) {
    $target = Join-Path $snapshot $name
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath (Join-Path $project $name) -Destination $target
}
$snapshotInputs = & python (Join-Path $snapshot 'tools/stage_release.py') --fingerprint
if ($LASTEXITCODE -or (Compare-Object $inventory $snapshotInputs)) { throw 'Source changed during the snapshot.' }
New-Item -ItemType Junction -Path (Join-Path $snapshot '.local') -Target (Join-Path $project '.local') | Out-Null
$gradleText = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $snapshot 'app/build.gradle')
$version = [regex]::Match($gradleText, "versionName '([0-9.]+)'").Groups[1].Value
if (!$version) { throw 'Missing version name.' }
$pin = '40d36629d9780396e88d286fa6f43771f8d663fbc0b9e9f951ad84c5153ddf4e'
$gradleArgs = @('--no-daemon','--max-workers=1','--console=plain','-Dorg.gradle.jvmargs=-Xmx384m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8')
if ($Offline) { $gradleArgs += '--offline' }
Push-Location -LiteralPath $snapshot
try {
    foreach ($edition in @('full', 'offline')) {
        $argsForEdition = $gradleArgs + @(':app:assembleRelease')
        if ($edition -eq 'full') { $argsForEdition += @(':app:assembleDebugAndroidTest','-PofflineOnly=false') }
        else { $argsForEdition += '-PofflineOnly=true' }
        & .\gradlew.bat @argsForEdition
        if ($LASTEXITCODE) { throw "$edition candidate build failed." }
        $suffix = if ($edition -eq 'offline') { '-offline' } else { '' }
        $apk = Join-Path $destination "ClearScreen-$version$suffix.apk"
        Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apk
        & python tools/verify_apk.py $apk --variant $edition --build-type release --expected-signer $pin
        if ($LASTEXITCODE) { throw "$edition package verification failed." }
    }
    $sdkConfig = (Get-Content -LiteralPath 'local.properties' -Raw | ConvertFrom-StringData)
    $sdk = $sdkConfig['sdk.dir']
    $signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName 'lib/apksigner.jar')) } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    $jar = Join-Path $signer.FullName 'lib/apksigner.jar'
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
    $cfg = Get-Content -LiteralPath '.local/signing.properties' -Raw | ConvertFrom-StringData
    $keystore = [IO.Path]::GetFullPath($cfg.storeFile, (Join-Path $snapshot 'app'))
    $env:CLEARSCREEN_TEST_SIGN_STORE_PASS = $cfg.storePassword
    $env:CLEARSCREEN_TEST_SIGN_KEY_PASS = $cfg.keyPassword
    $testApk = Join-Path $destination "ClearScreen-$version-androidTest.apk"
    try {
        & $java -Xmx128m -jar $jar sign --ks $keystore --ks-key-alias $cfg.keyAlias --ks-pass env:CLEARSCREEN_TEST_SIGN_STORE_PASS --key-pass env:CLEARSCREEN_TEST_SIGN_KEY_PASS --out $testApk 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        if ($LASTEXITCODE) { throw 'Instrumentation signing failed.' }
    } finally {
        Remove-Item Env:CLEARSCREEN_TEST_SIGN_STORE_PASS,Env:CLEARSCREEN_TEST_SIGN_KEY_PASS -ErrorAction SilentlyContinue
    }
    $certs = @(& $java -Xmx128m -jar $jar verify --print-certs $testApk | Select-String 'certificate SHA-256 digest:')
    if ($LASTEXITCODE -or $certs.Count -ne 1 -or "$($certs[0])" -notmatch "$pin$") { throw 'Instrumentation certificate mismatch.' }
    Get-ChildItem -LiteralPath $destination -Filter '*.apk' | Get-FileHash -Algorithm SHA256
} finally {
    Pop-Location
}
