# 与 core 里的 AndroidJavaCatalog.ABIS 对齐，但少一个 x86：
# 现在没有 x86 的 Android 设备在跑 Minecraft，多打一个 ABI 只是让 APK 白白变大。
APP_ABI := arm64-v8a armeabi-v7a x86_64

# 与 minSdk 一致。
APP_PLATFORM := android-26
