// 应用内起 JVM 的那一层。它只做三件事：把 libjvm.so 及其依赖从运行时目录里加载起来、
// 调 JNI_CreateJavaVM 造一个 JVM、把游戏主类的 main 跑起来。
//
// 为什么这一层非写 C 不可：JVM 的创建入口 JNI_CreateJavaVM 是个 C 符号，Android 的
// Java 层没有任何办法把另一个 JVM 放进本进程。Kotlin 与 Java 侧的每一行都跑在 ART 上，
// 而游戏要跑在 HotSpot 上，这两套运行时只能在 native 层接起来。
//
// 为什么返回码是一串负数而不是抛异常：C 层跨不到 Kotlin 的异常体系，而每一种失败
// （找不到文件 / dlopen 被拒 / 版本对不上 / 主类不对）在界面上要说的完全是不同的话，
// 只有把阶段信息带回来，Kotlin 侧才讲得清。文案在 JvmBridgeCodes 里。

#include <jni.h>
#include <dirent.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#define LOG_TAG "PlatypusJvm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 返回码。0 是成功；负数按阶段分，Kotlin 侧逐条翻成人话。
#define OK 0
#define ERR_ARGS -1
#define ERR_NO_LIBJVM -2
#define ERR_NO_ENTRY -3
#define ERR_CREATE_FAILED -4
#define ERR_NO_MAIN_CLASS -5
#define ERR_NO_MAIN_METHOD -6
#define ERR_MAIN_THREW -7
#define ERR_NO_MEMORY -8

// 上一次失败的详情。Kotlin 侧在拿到负返回码后再调一次 nativeLastError() 取它，
// 这样界面上不只有「第几步失败」，还有 dlerror / 异常堆栈那种一眼能查到根因的东西。
static char g_last_error[4096];
static JavaVM *g_vm = NULL;

static void set_error(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(g_last_error, sizeof(g_last_error), fmt, args);
    va_end(args);
    LOGE("%s", g_last_error);
}

// 把 Android 那套 JNIEnv 里的 jstring 抄成 C 字符串。注意这些字符串必须在
// 创建 JVM 之前就抄出来 —— 新 JVM 用不了旧 JVM 的 jobject（两个运行时各有各的堆）。
static char *copy_string(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) return NULL;
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

// 目录扫描用的名字表。按名字排序，好让 libjvm.so 稳定地排在最后加载 ——
// 依赖先就位，最后才是需要它们的那个。
typedef struct {
    char **names;
    int count;
} NameList;

static int compare_names(const void *a, const void *b) {
    return strcmp(*(const char **) a, *(const char **) b);
}

static NameList list_so_files(const char *dir) {
    NameList list;
    list.names = NULL;
    list.count = 0;

    DIR *handle = opendir(dir);
    if (handle == NULL) return list;

    int capacity = 0;
    struct dirent *entry;
    while ((entry = readdir(handle)) != NULL) {
        const char *name = entry->d_name;
        size_t length = strlen(name);
        if (length < 4) continue;
        if (strcmp(name + length - 3, ".so") != 0) continue;

        if (list.count >= capacity) {
            capacity = capacity == 0 ? 32 : capacity * 2;
            char **grown = realloc(list.names, sizeof(char *) * (size_t) capacity);
            if (grown == NULL) break;
            list.names = grown;
        }
        list.names[list.count] = strdup(name);
        if (list.names[list.count] != NULL) list.count++;
    }
    closedir(handle);

    if (list.count > 1) {
        qsort(list.names, (size_t) list.count, sizeof(char *), compare_names);
    }
    return list;
}

static void free_names(NameList *list) {
    for (int i = 0; i < list->count; i++) free(list->names[i]);
    free(list->names);
    list->names = NULL;
    list->count = 0;
}

// 加载一个目录里的全部 .so，返回 libjvm.so 的句柄。逐条记失败但不中止：
// 有些库里带了当前 CPU 用不上的指令，或者压根是给别的架构准备的，跳过它们
// 比整个中止更接近「尽量起来」。真正决定成败的是最后能不能 dlsym 到入口。
static void *load_runtime_libs(const char *dir, const char *jvm_name) {
    NameList list = list_so_files(dir);
    void *jvm_handle = NULL;

    for (int i = 0; i < list.count; i++) {
        char path[4096];
        snprintf(path, sizeof(path), "%s/%s", dir, list.names[i]);

        // RTLD_GLOBAL 是关键：libjvm.so 的 DT_NEEDED 里写着 libjava.so 这类名字，
        // 靠全局符号表命中已经加载进来的那一份，它才不会又去找一个找不到的路径。
        void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (handle == NULL) {
            LOGI("skip %s: %s", list.names[i], dlerror());
            continue;
        }
        if (jvm_name != NULL && strcmp(list.names[i], jvm_name) == 0) {
            jvm_handle = handle;
        }
    }

    free_names(&list);
    return jvm_handle;
}

// 从游戏参数表里挑出 JVM 认的那部分，拼成 JavaVMInitArgs。
// 返回 0 表示一切就绪。options_out 由调用方 free。
static int build_options(JNIEnv *env, jobjectArray options, JavaVMOption **options_out, int *count_out) {
    jsize count = options == NULL ? 0 : (*env)->GetArrayLength(env, options);
    if (count == 0) return ERR_ARGS;

    JavaVMOption *array = calloc((size_t) count, sizeof(JavaVMOption));
    if (array == NULL) return ERR_NO_MEMORY;

    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, options, i);
        char *text = copy_string(env, item);
        (*env)->DeleteLocalRef(env, item);
        if (text == NULL) {
            array[i].optionString = strdup("-Dplatypus.empty=1");
        } else {
            array[i].optionString = text;
        }
        array[i].extraInfo = NULL;
    }

    *options_out = array;
    *count_out = (int) count;
    return OK;
}

static void free_options(JavaVMOption *options, int count) {
    if (options == NULL) return;
    for (int i = 0; i < count; i++) free((void *) options[i].optionString);
    free(options);
}

// 主类名要从 Java 的点号写法换成 JNI 的斜杠写法，否则 FindClass 一定返回 NULL。
static char *to_jni_class_name(const char *raw) {
    char *copy = strdup(raw);
    if (copy == NULL) return NULL;
    for (char *p = copy; *p != '\0'; p++) {
        if (*p == '.') *p = '/';
    }
    return copy;
}

JNIEXPORT jint JNICALL
Java_io_github_huang404yan_platypus_launcher_runtime_JvmBridge_nativeStart(
        JNIEnv *env, jclass clazz, jstring libDir, jstring jvmFile,
        jobjectArray options, jstring mainClass, jobjectArray appArgs) {
    (void) clazz;
    g_last_error[0] = '\0';

    char *dir = copy_string(env, libDir);
    char *jvm_name = copy_string(env, jvmFile);
    char *main_name = copy_string(env, mainClass);
    if (dir == NULL || jvm_name == NULL || main_name == NULL) {
        set_error("参数缺失：libDir / libjvm 文件名 / 主类名三个都不能为空。");
        free(dir); free(jvm_name); free(main_name);
        return ERR_ARGS;
    }

    void *jvm_handle = load_runtime_libs(dir, jvm_name);
    if (jvm_handle == NULL) {
        set_error("没能在 %s 里加载 %s。最后一次 dlopen 的报错见上一条日志；"
                  "Android 10 起禁止从可写目录 dlopen .so，这通常就是原因。", dir, jvm_name);
        free(dir); free(jvm_name); free(main_name);
        return ERR_NO_LIBJVM;
    }

    typedef jint (JNICALL *CreateJavaVmFn)(JavaVM **, void **, void *);
    CreateJavaVmFn create = (CreateJavaVmFn) dlsym(jvm_handle, "JNI_CreateJavaVM");
    if (create == NULL) {
        set_error("%s 里没有 JNI_CreateJavaVM 这个符号，它多半不是一份完整的 JVM。", jvm_name);
        free(dir); free(jvm_name); free(main_name);
        return ERR_NO_ENTRY;
    }

    JavaVMOption *option_array = NULL;
    int option_count = 0;
    int built = build_options(env, options, &option_array, &option_count);
    if (built != OK) {
        set_error("拼 JVM 启动参数失败（%d）。", built);
        free(dir); free(jvm_name); free(main_name);
        return built;
    }

    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_6;
    vm_args.nOptions = option_count;
    vm_args.options = option_array;
    // 认不出来的参数直接失败而不是忽略：忽略会让某条 -D 悄悄不生效，
    // 表现成「游戏起来了但行为不对」，那种问题比起不来难查得多。
    vm_args.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *jni = NULL;
    jint created = create(&vm, (void **) &jni, &vm_args);
    free_options(option_array, option_count);
    if (created != JNI_OK) {
        set_error("JNI_CreateJavaVM 返回 %d（负数都是失败：-1 未知、-2 线程已附着、"
                  "-3 JNI 版本不支持、-4 内存不足、-5 虚拟机已存在、-6 版本不匹配）。", (int) created);
        free(dir); free(jvm_name); free(main_name);
        return ERR_CREATE_FAILED;
    }
    g_vm = vm;

    char *jni_main_name = to_jni_class_name(main_name);
    jclass main_cls = (*jni)->FindClass(jni, jni_main_name);
    if (main_cls == NULL) {
        set_error("JVM 起来了，但在 classpath 里找不到主类 %s。多半是 -Djava.class.path 里"
                  "少了客户端 jar，或者版本文件里的 mainClass 写错了。", main_name);
        free(dir); free(jvm_name); free(main_name); free(jni_main_name);
        return ERR_NO_MAIN_CLASS;
    }

    jmethodID main_method = (*jni)->GetStaticMethodID(jni, main_cls, "main", "([Ljava/lang/String;)V");
    if (main_method == NULL) {
        set_error("主类 %s 上没有 public static void main(String[])。", main_name);
        free(dir); free(jvm_name); free(main_name); free(jni_main_name);
        return ERR_NO_MAIN_METHOD;
    }

    jsize arg_count = appArgs == NULL ? 0 : (*env)->GetArrayLength(env, appArgs);
    jclass string_cls = (*jni)->FindClass(jni, "java/lang/String");
    jobjectArray jni_args = (*jni)->NewObjectArray(jni, arg_count, string_cls, NULL);
    for (jsize i = 0; i < arg_count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, appArgs, i);
        char *text = copy_string(env, item);
        (*env)->DeleteLocalRef(env, item);
        if (text == NULL) continue;
        jstring converted = (*jni)->NewStringUTF(jni, text);
        (*jni)->SetObjectArrayElement(jni, jni_args, i, converted);
        (*jni)->DeleteLocalRef(jni, converted);
        free(text);
    }

    LOGI("starting %s with %d options and %d game arguments", main_name, option_count, (int) arg_count);
    (*jni)->CallStaticVoidMethod(jni, main_cls, main_method, jni_args);

    jint result = OK;
    if ((*jni)->ExceptionCheck(jni)) {
        (*jni)->ExceptionDescribe(jni);
        (*jni)->ExceptionClear(jni);
        set_error("游戏主类抛出了异常（完整堆栈在上面的日志里）。");
        result = ERR_MAIN_THREW;
    }

    free(dir);
    free(jvm_name);
    free(main_name);
    free(jni_main_name);
    return result;
}

JNIEXPORT jstring JNICALL
Java_io_github_huang404yan_platypus_launcher_runtime_JvmBridge_nativeLastError(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, g_last_error);
}
