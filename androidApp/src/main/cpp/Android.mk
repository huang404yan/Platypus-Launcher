LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

# 只用最朴素的 ndk-build：这一层只有一个 .c 文件、零依赖，为它装一套 CMake 不划算。
LOCAL_MODULE := platypus-jvm
LOCAL_SRC_FILES := jvm_bridge.c

# -llog 是为了把失败原因写进 logcat：这一层跑在 native 里，Kotlin 拿不到 dlerror 的原文。
# -ldl 是 dlopen / dlsym。
LOCAL_LDLIBS := -llog -ldl

include $(BUILD_SHARED_LIBRARY)
