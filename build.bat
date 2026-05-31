@echo off
REM Сборка MAX Mini APK
REM Перед запуском: установите Android SDK и настройте ANDROID_HOME

cd /d "%~dp0"

REM Проверка Gradle Wrapper
if not exist gradlew.bat (
    echo Создание Gradle Wrapper...
    gradle wrapper --gradle-version 8.2
)

echo Сборка release APK...
call gradlew assembleRelease

echo.
echo Готово: app\build\outputs\apk\release\app-release.apk
pause
