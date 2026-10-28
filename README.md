# JARVIS Mobile

Android assistant prototype for ASUS ROG Phone 6, built with Kotlin and Jetpack Compose.

## Build APK

GitHub Actions builds a debug APK and stores it as an artifact. Open the **Actions** tab, select **Build JARVIS Mobile APK**, and run the workflow.

## Current project setup

The Android source project is provided in `JarvisMobile-source.zip`. The workflow extracts it and runs `gradle assembleDebug` using Java 17 and Android SDK 35.

Firebase AI features require your own Firebase project configuration. Do not commit private API keys or credentials.
