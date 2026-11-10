# InkFlow Studio — Build & Test Guide

## 1. Prerequisites
- **Operating System:** macOS, Linux, or Windows (tested on macOS Darwin aarch64)
- **JDK:** Java 17 or Java 21 (Temurin JDK 21 recommended)
- **Android SDK:** API 35 or 36 platform installed
- **Android Build Tools:** 35.0.0 or 36.1.0

## 2. Environment Setup
Set your environment variables:
```bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

Create `local.properties` in the project root:
```properties
sdk.dir=/Users/pankajmeena/Library/Android/sdk
```

## 3. Build Commands
To compile the debug APK:
```bash
./gradlew assembleDebug
```
Output artifact path:
`app/build/outputs/apk/debug/app-debug.apk`

## 4. Test Commands
To execute unit tests:
```bash
./gradlew testDebugUnitTest
```

To run lint checks:
```bash
./gradlew lintDebug
```

