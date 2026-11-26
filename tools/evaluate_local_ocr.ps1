#requires -Version 5.1
# Run with Windows PowerShell and installed zh-CN OCR. Input is a folder of opt-in cropped PNGs.
# Results contain only the file's numeric case ID, known exit-word presence and elapsed time.
param([Parameter(Mandatory=$true)][string]$Crops)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.SoftwareBitmap,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$null = [Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime]
$bridge = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetGenericArguments().Count -eq 1 -and
  $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await-Result($Operation,[Type]$Type) {
  $task=$bridge.MakeGenericMethod($Type).Invoke($null,@($Operation))
  $task.GetAwaiter().GetResult()
}
$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('zh-CN'))
if ($null -eq $engine) {throw 'Local zh-CN OCR unavailable'}
foreach ($item in Get-ChildItem -LiteralPath $Crops -File -Filter '*.png') {
  if($item.BaseName -notmatch '^\d+$'){continue}
  $file=Await-Result ([Windows.Storage.StorageFile]::GetFileFromPathAsync($item.FullName)) ([Windows.Storage.StorageFile])
  $stream=Await-Result ($file.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
  try {
    $decoder=Await-Result ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap=Await-Result ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    try {
      $watch=[Diagnostics.Stopwatch]::StartNew()
      $result=Await-Result ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
      $watch.Stop()
      $contains=[regex]::Replace($result.Text,'\s','') -match '\u8df3\u8fc7|\u8df3\u904e|\u5173\u95ed\u5e7f\u544a|skipad'
      [pscustomobject]@{id=$item.BaseName;recognized_exit=$contains;elapsed_ms=$watch.ElapsedMilliseconds} | ConvertTo-Json -Compress
    } finally {$bitmap.Dispose()}
  } finally {$stream.Dispose()}
}
