@echo off
chcp 65001 >nul
title XhsAgent Build
echo ============================================
echo   XhsAgent - 构建脚本
echo ============================================
echo.

echo 推荐使用 Android Studio 打开项目一键构建
echo.
echo 如果你仍想在命令行构建，请按以下步骤:
echo.
echo 步骤 1: 安装 JDK 17
echo    winget install EclipseAdoptium.Temurin.17.JDK
echo.
echo 步骤 2: 下载 Android Studio 或命令行工具
echo    https://developer.android.com/studio
echo.
echo 步骤 3: 打开 Android Studio -^> File -^> Open
echo          选择 D:\git\mobile-agent
echo.
echo 步骤 4: Android Studio 会自动下载:
echo   • Gradle Wrapper (gradle-wrapper.jar)
echo   • Android SDK 29
echo   • Build Tools 29.0.3
echo.
echo 步骤 5: 编辑 local.properties 添加 API Key:
echo    DEEPSEEK_API_KEY=sk-your-key-here
echo.
echo 步骤 6: Build -^> Build Bundle(s) / APK(s)
echo           -^> Build APK(s)
echo.
echo APK 编译完成后会自动弹出文件位置
echo.
pause
