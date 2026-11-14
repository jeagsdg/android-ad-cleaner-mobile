@echo off
setlocal EnableExtensions DisableDelayedExpansion
"%SystemRoot%\System32\chcp.com" 65001 >nul
title ClearScreen USB Setup

set "PACKAGE=app.clearscreen"
set "PERMISSION=android.permission.WRITE_SECURE_SETTINGS"
set "RESULT=1"

echo 净屏 USB 授权助手
echo.
echo 请先在小米、Redmi 或 POCO 手机上安装净屏，打开「USB 调试」。
echo 用数据线连接一台手机，解锁手机，只在信任这台电脑时点「允许 USB 调试」。
echo.

set "ADB="
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"
if not defined ADB for /f "delims=" %%A in ('%SystemRoot%\System32\where.exe $PATH:adb.exe 2^>nul') do if not defined ADB set "ADB=%%A"
if not defined ADB goto :missing_adb

rem Always use the local ADB server and select a single USB transport.
set "ADB_SERVER_SOCKET="
set "ANDROID_ADB_SERVER_ADDRESS="
set "ANDROID_ADB_SERVER_PORT="
set "ANDROID_SERIAL="
set "USB_STATE="
for /f "delims=" %%S in ('^( "%ADB%" -d get-state 2^>nul ^)') do set "USB_STATE=%%S"
setlocal EnableDelayedExpansion
if not "!USB_STATE!"=="device" goto :usb_error
endlocal
call :read_serial DEVICE_SERIAL
if not defined DEVICE_SERIAL goto :usb_error
call :read_user ANDROID_USER
if not defined ANDROID_USER goto :user_error

"%ADB%" -d shell pm path --user %ANDROID_USER% %PACKAGE% | "%SystemRoot%\System32\findstr.exe" /b /c:"package:" >nul
if errorlevel 1 goto :app_missing

echo 「自动恢复无障碍」需要让净屏在服务被系统关闭时尝试恢复自己的开关。
echo 将向净屏授予系统设置写入权限 WRITE_SECURE_SETTINGS。
echo 授权完成后，请回到净屏手动启用「自动恢复无障碍」。
echo.
echo 确认授权？按 Y 授权，按 N 取消。
"%SystemRoot%\System32\choice.exe" /c YN /n
if errorlevel 2 goto :cancelled
if not errorlevel 1 goto :cancelled

call :read_serial CURRENT_SERIAL
if not defined CURRENT_SERIAL goto :target_changed
if not "%DEVICE_SERIAL%"=="%CURRENT_SERIAL%" goto :target_changed
call :read_user CURRENT_USER
if not defined CURRENT_USER goto :target_changed
if not "%ANDROID_USER%"=="%CURRENT_USER%" goto :target_changed

rem Pin the USB device selected above so a different phone cannot receive the grant.
"%ADB%" -s "%DEVICE_SERIAL%" shell pm grant --user %ANDROID_USER% %PACKAGE% %PERMISSION%
if errorlevel 1 goto :grant_failed

set "GRANT_STATUS="
for /f "delims=" %%C in ('^( "%ADB%" -s "%DEVICE_SERIAL%" shell dumpsys package check-permission %PERMISSION% %PACKAGE% %ANDROID_USER% ^)') do set "GRANT_STATUS=%%C"
setlocal EnableDelayedExpansion
if not "!GRANT_STATUS!"=="0" goto :verify_in_app
endlocal

echo.
echo 已授予净屏所需权限。打开净屏首页，点击「无障碍掉线？自动恢复」，按提示继续。
echo 完成后，可在手机的开发者选项中关闭 USB 调试。
set "RESULT=0"
goto :finish

:read_serial
set "%~1="
for /f "delims=" %%S in ('^( "%ADB%" -d get-serialno 2^>nul ^| "%SystemRoot%\System32\findstr.exe" /r /x /c:"[0-9A-Za-z][0-9A-Za-z._-]*" ^)') do set "%~1=%%S"
exit /b

:read_user
set "%~1="
setlocal DisableDelayedExpansion
set "USER_ID="
for /f "delims=" %%U in ('^( "%ADB%" -d shell am get-current-user 2^>nul ^)') do set "USER_ID=%%U"
if not defined USER_ID exit /b
setlocal EnableDelayedExpansion
set "NON_DIGITS=!USER_ID!"
for %%D in (0 1 2 3 4 5 6 7 8 9) do if defined NON_DIGITS set "NON_DIGITS=!NON_DIGITS:%%D=!"
if defined NON_DIGITS exit /b
endlocal & endlocal & set "%~1=%USER_ID%"
exit /b

:missing_adb
echo 找不到 adb.exe。请下载并解压 Google 官方 Android SDK Platform-Tools（Windows 版）。
echo https://developer.android.com/tools/releases/platform-tools
echo 把本助手放入解压后的 platform-tools 文件夹，再双击它。
goto :finish

:usb_error
echo 无法通过 USB 识别一台已允许调试的手机。ADB 信息如下。
"%ADB%" -d get-state
echo.
echo 请只保留一台手机连接，检查数据线，解锁并查看手机上的「允许 USB 调试」提示。
goto :finish

:user_error
echo 无法读取手机当前用户。请解锁手机，切回主空间后重试。
goto :finish

:app_missing
echo 无法确认净屏已安装在手机当前空间。请安装或打开净屏后重试。
goto :finish

:cancelled
echo 已取消授权。
set "RESULT=0"
goto :finish

:target_changed
echo 检测到 USB 手机或手机当前用户发生变化。请重新运行助手。
goto :finish

:grant_failed
echo.
echo 授权失败。如果开发者选项中有「USB 调试（安全设置）」，请打开后重试。
goto :finish

:verify_in_app
echo 授权命令已完成。请打开净屏首页，点击「无障碍掉线？自动恢复」，确认权限状态。

:finish
echo.
echo 按任意键关闭此窗口。
pause >nul
exit /b %RESULT%
