#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

typedef struct {
    JavaVM *vm;
    JNIEnv *env;
    jobject thread;
    jmethodID is_interrupted;
    jobject host;
    jmethodID call_method;
    jmethodID emit_method;
    int64_t deadline_ms;
} HostState;

static int64_t monotonic_millis(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return (int64_t)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

static JNIEnv *current_env(HostState *state, int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*state->vm)->GetEnv(state->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*state->vm)->AttachCurrentThread(state->vm, &env, NULL) != JNI_OK) return NULL;
        *attached = 1;
    }
    return env;
}

/* JNI modified UTF-8 is not ECMAScript UTF-8 (notably emoji and NUL).
 * Cross the boundary with the real UTF-8 charset, including error messages. */
static char *java_utf8(JNIEnv *env, jstring value) {
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jmethodID method = (*env)->GetMethodID(env, cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    jbyteArray bytes = (jbyteArray)(*env)->CallObjectMethod(env, value, method, encoding);
    (*env)->DeleteLocalRef(env, encoding);
    (*env)->DeleteLocalRef(env, cls);
    if (!bytes || (*env)->ExceptionCheck(env)) return NULL;
    jsize length = (*env)->GetArrayLength(env, bytes);
    char *result = malloc((size_t)length + 1);
    if (result) { (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)result); result[length] = 0; }
    (*env)->DeleteLocalRef(env, bytes);
    return result;
}

static jstring new_java_utf8_length(JNIEnv *env, const char *value, size_t length) {
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (!bytes) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)value);
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jmethodID constructor = (*env)->GetMethodID(env, cls, "<init>", "([BLjava/lang/String;)V");
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    jstring result = (jstring)(*env)->NewObject(env, cls, constructor, bytes, encoding);
    (*env)->DeleteLocalRef(env, encoding); (*env)->DeleteLocalRef(env, bytes); (*env)->DeleteLocalRef(env, cls);
    return result;
}

static jstring new_java_utf8(JNIEnv *env, const char *value) {
    return new_java_utf8_length(env, value, strlen(value));
}

static void throw_runtime(JNIEnv *env, const char *message) {
    jclass error = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (error != NULL) {
        jmethodID constructor = (*env)->GetMethodID(env, error, "<init>", "(Ljava/lang/String;)V");
        jstring text = new_java_utf8(env, message != NULL ? message : "QuickJS runtime error");
        jthrowable exception = (jthrowable)(*env)->NewObject(env, error, constructor, text);
        if (exception) (*env)->Throw(env, exception);
        (*env)->DeleteLocalRef(env, text); (*env)->DeleteLocalRef(env, exception); (*env)->DeleteLocalRef(env, error);
    }
}

static void clear_java_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        jthrowable exception = (*env)->ExceptionOccurred(env);
        (*env)->ExceptionClear(env);
        if (exception != NULL) (*env)->DeleteLocalRef(env, exception);
    }
}

static JSValue throw_js_error(JSContext *ctx, const char *message) {
    return JS_ThrowInternalError(ctx, "%s", message != NULL ? message : "QuickJS runtime error");
}

static char *exception_text(JSContext *ctx) {
    JSValue exception = JS_GetException(ctx);
    const char *text = JS_ToCString(ctx, exception);
    char *copy = text != NULL ? strdup(text) : NULL;
    if (text != NULL) JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, exception);
    return copy;
}

static JSValue js_hanwo_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1 || !JS_IsString(argv[0])) return throw_js_error(ctx, "hanwo.call(command,args) requires a command name");
    HostState *state = (HostState *)JS_GetContextOpaque(ctx);
    if (state == NULL) return throw_js_error(ctx, "QuickJS host is unavailable");

    const char *command = JS_ToCString(ctx, argv[0]);
    if (command == NULL) return JS_EXCEPTION;
    JSValue args_value = argc >= 2 ? JS_DupValue(ctx, argv[1]) : JS_NewObject(ctx);
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue json_value = JS_GetPropertyStr(ctx, global, "JSON");
    JS_FreeValue(ctx, global);
    JSValue stringify = JS_GetPropertyStr(ctx, json_value, "stringify");
    JSValue stringify_args[1] = {args_value};
    JSValue args_json_value = JS_Call(ctx, stringify, json_value, 1, stringify_args);
    JS_FreeValue(ctx, stringify);
    JS_FreeValue(ctx, json_value);
    JS_FreeValue(ctx, args_value);
    if (JS_IsException(args_json_value)) {
        JS_FreeCString(ctx, command);
        return JS_EXCEPTION;
    }
    const char *args_json = JS_ToCString(ctx, args_json_value);
    JS_FreeValue(ctx, args_json_value);
    if (args_json == NULL) {
        JS_FreeCString(ctx, command);
        return JS_EXCEPTION;
    }

    int attached = 0;
    JNIEnv *env = current_env(state, &attached);
    if (env == NULL) {
        JS_FreeCString(ctx, command);
        JS_FreeCString(ctx, args_json);
        return throw_js_error(ctx, "无法附加 QuickJS 宿主线程");
    }
    jstring command_string = new_java_utf8(env, command);
    jstring args_string = new_java_utf8(env, args_json);
    if (command_string == NULL || args_string == NULL) {
        if (command_string != NULL) (*env)->DeleteLocalRef(env, command_string);
        if (args_string != NULL) (*env)->DeleteLocalRef(env, args_string);
        JS_FreeCString(ctx, command);
        JS_FreeCString(ctx, args_json);
        clear_java_exception(env);
        if (attached) (*state->vm)->DetachCurrentThread(state->vm);
        return throw_js_error(ctx, "运行时命令参数编码失败");
    }
    jstring result_string = (jstring)(*env)->CallObjectMethod(env, state->host, state->call_method, command_string, args_string);
    (*env)->DeleteLocalRef(env, command_string);
    (*env)->DeleteLocalRef(env, args_string);
    JS_FreeCString(ctx, command);
    JS_FreeCString(ctx, args_json);
    if ((*env)->ExceptionCheck(env)) {
        jthrowable exception = (*env)->ExceptionOccurred(env);
        (*env)->ExceptionClear(env);
        jclass cls = (*env)->GetObjectClass(env, exception);
        jmethodID get_message = (*env)->GetMethodID(env, cls, "getMessage", "()Ljava/lang/String;");
        jstring message = (jstring)(*env)->CallObjectMethod(env, exception, get_message);
        char *detail = message ? java_utf8(env, message) : NULL;
        JSValue failure = JS_ThrowInternalError(ctx, "%s", detail ? detail : "runtime_failed: 运行时命令执行失败");
        free(detail);
        (*env)->DeleteLocalRef(env, message); (*env)->DeleteLocalRef(env, cls); (*env)->DeleteLocalRef(env, exception);
        clear_java_exception(env);
        if (attached) (*state->vm)->DetachCurrentThread(state->vm);
        return failure;
    }
    if (result_string == NULL) {
        if (attached) (*state->vm)->DetachCurrentThread(state->vm);
        return throw_js_error(ctx, "运行时命令没有返回结果");
    }
    const char *result_json = java_utf8(env, result_string);
    JSValue result = result_json == NULL
        ? JS_EXCEPTION
        : JS_ParseJSON(ctx, result_json, strlen(result_json), "<runtime-result>");
    if (result_json != NULL) free((void *)result_json);
    (*env)->DeleteLocalRef(env, result_string);
    if (attached) (*state->vm)->DetachCurrentThread(state->vm);
    return result;
}

static JSValue js_named_command(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                               int magic, JSValue *data) {
    (void)magic;
    JSValueConst args[] = {data[0], argc > 0 ? argv[0] : JS_UNDEFINED};
    return js_hanwo_call(ctx, this_val, argc > 0 ? 2 : 1, args);
}

static int install_named_commands(JNIEnv *env, JSContext *ctx, JSValueConst hanwo, jobjectArray commands) {
    jsize count = (*env)->GetArrayLength(env, commands);
    for (jsize index = 0; index < count; index++) {
        jstring command = (jstring)(*env)->GetObjectArrayElement(env, commands, index);
        if (command == NULL) return -1;
        const char *name = java_utf8(env, command);
        int status = -1;
        if (name != NULL) {
            JSValue data = JS_NewString(ctx, name);
            if (!JS_IsException(data)) {
                JSValue function = JS_NewCFunctionData(ctx, js_named_command, 1, 0, 1, &data);
                if (!JS_IsException(function)) status = JS_SetPropertyStr(ctx, hanwo, name, function);
                JS_FreeValue(ctx, data);
            }
            free((void *)name);
        }
        (*env)->DeleteLocalRef(env, command);
        if (status < 0) return status;
    }
    return 0;
}

static JSValue js_hanwo_emit(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    HostState *state = (HostState *)JS_GetContextOpaque(ctx);
    if (state == NULL) return throw_js_error(ctx, "QuickJS host is unavailable");
    JSValue value = argc >= 1 ? argv[0] : JS_NewString(ctx, "");
    size_t text_length;
    const char *text = JS_ToCStringLen(ctx, &text_length, value);
    if (argc < 1) JS_FreeValue(ctx, value);
    if (text == NULL) return JS_EXCEPTION;
    int attached = 0;
    JNIEnv *env = current_env(state, &attached);
    if (env == NULL) {
        JS_FreeCString(ctx, text);
        return throw_js_error(ctx, "无法附加 QuickJS 宿主线程");
    }
    jstring output = new_java_utf8_length(env, text, text_length);
    if (output == NULL) {
        JS_FreeCString(ctx, text);
        clear_java_exception(env);
        if (attached) (*state->vm)->DetachCurrentThread(state->vm);
        return throw_js_error(ctx, "输出编码失败");
    }
    (*env)->CallVoidMethod(env, state->host, state->emit_method, output);
    (*env)->DeleteLocalRef(env, output);
    JS_FreeCString(ctx, text);
    if ((*env)->ExceptionCheck(env)) {
        clear_java_exception(env);
        if (attached) (*state->vm)->DetachCurrentThread(state->vm);
        return JS_ThrowInternalError(ctx, "输出回调失败");
    }
    if (attached) (*state->vm)->DetachCurrentThread(state->vm);
    return JS_UNDEFINED;
}

static int quickjs_interrupt(JSRuntime *runtime, void *opaque) {
    (void)runtime;
    HostState *state = (HostState *)opaque;
    return state != NULL && (monotonic_millis() >= state->deadline_ms || (*state->env)->CallBooleanMethod(state->env, state->thread, state->is_interrupted));
}

JNIEXPORT jstring JNICALL
Java_com_agent_voiceassistant_tools_runtime_QuickJsNative_execute(
    JNIEnv *env,
    jobject object,
    jstring source_string,
    jstring input_string,
    jobject host,
    jobjectArray commands,
    jint timeout_ms,
    jlong memory_bytes) {
    (void)object;
    if (source_string == NULL || input_string == NULL || host == NULL || commands == NULL) {
        throw_runtime(env, "QuickJS 参数不能为空");
        return NULL;
    }
    const char *source = java_utf8(env, source_string);
    const char *input = java_utf8(env, input_string);
    if (source == NULL || input == NULL) {
        if (source != NULL) free((void *)source);
        if (input != NULL) free((void *)input);
        throw_runtime(env, "QuickJS 输入编码失败");
        return NULL;
    }

    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    jclass host_class = (*env)->GetObjectClass(env, host);
    jmethodID call_method = (*env)->GetMethodID(env, host_class, "call", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jmethodID emit_method = (*env)->GetMethodID(env, host_class, "emit", "(Ljava/lang/String;)V");
    if (call_method == NULL || emit_method == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, host_class);
        free((void *)source);
        free((void *)input);
        throw_runtime(env, "QuickJS 宿主回调接口不匹配");
        return NULL;
    }
    (*env)->DeleteLocalRef(env, host_class);

    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
    jmethodID current_thread = (*env)->GetStaticMethodID(env, thread_class, "currentThread", "()Ljava/lang/Thread;");
    jobject thread = (*env)->CallStaticObjectMethod(env, thread_class, current_thread);
    jmethodID is_interrupted = (*env)->GetMethodID(env, thread_class, "isInterrupted", "()Z");
    (*env)->DeleteLocalRef(env, thread_class);
    HostState state = {
        .vm = vm,
        .env = env,
        .thread = thread,
        .is_interrupted = is_interrupted,
        .host = (*env)->NewGlobalRef(env, host),
        .call_method = call_method,
        .emit_method = emit_method,
        .deadline_ms = monotonic_millis() + timeout_ms,
    };
    JSRuntime *runtime = JS_NewRuntime();
    JSContext *ctx = runtime != NULL ? JS_NewContext(runtime) : NULL;
    jstring result_string = NULL;
    if (runtime == NULL || ctx == NULL) {
        throw_runtime(env, "无法创建 QuickJS Runtime");
        goto cleanup;
    }
    JS_SetMemoryLimit(runtime, (size_t)memory_bytes);
    JS_SetMaxStackSize(runtime, 256 * 1024);
    JS_SetInterruptHandler(runtime, quickjs_interrupt, &state);
    JS_SetContextOpaque(ctx, &state);

    JSValue global = JS_GetGlobalObject(ctx);
    JSValue hanwo = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, hanwo, "call", JS_NewCFunction(ctx, js_hanwo_call, "call", 2));
    JS_SetPropertyStr(ctx, hanwo, "emit", JS_NewCFunction(ctx, js_hanwo_emit, "emit", 1));
    if (install_named_commands(env, ctx, hanwo, commands) < 0) {
        JS_FreeValue(ctx, hanwo);
        JS_FreeValue(ctx, global);
        if (!(*env)->ExceptionCheck(env)) throw_runtime(env, "无法注册 QuickJS 命令");
        goto cleanup;
    }
    JS_SetPropertyStr(ctx, global, "hanwo", hanwo);
    JS_FreeValue(ctx, global);

    JSValue input_value = JS_ParseJSON(ctx, input, strlen(input), "<input>");
    if (JS_IsException(input_value)) {
        char *message = exception_text(ctx);
        throw_runtime(env, message != NULL ? message : "输入 JSON 无效");
        free(message);
        JS_FreeValue(ctx, input_value);
        goto cleanup;
    }
    JSValue evaluated = JS_Eval(ctx, source, strlen(source), "main.js", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(evaluated)) {
        char *message = exception_text(ctx);
        throw_runtime(env, message != NULL ? message : "QuickJS 脚本执行失败");
        free(message);
        JS_FreeValue(ctx, evaluated);
        JS_FreeValue(ctx, input_value);
        goto cleanup;
    }
    JS_FreeValue(ctx, evaluated);
    global = JS_GetGlobalObject(ctx);
    JSValue main_function = JS_GetPropertyStr(ctx, global, "main");
    JSValue call_args[1] = {input_value};
    JSValue returned = JS_IsFunction(ctx, main_function)
        ? JS_Call(ctx, main_function, global, 1, call_args)
        : throw_js_error(ctx, "QuickJS Skill 必须定义 main(input) 函数");
    JS_FreeValue(ctx, main_function);
    if (JS_IsException(returned)) {
        char *message = exception_text(ctx);
        throw_runtime(env, message != NULL ? message : "QuickJS main() 执行失败");
        free(message);
        JS_FreeValue(ctx, returned);
        JS_FreeValue(ctx, input_value);
        JS_FreeValue(ctx, global);
        goto cleanup;
    }
    if ((int)JS_PromiseState(ctx, returned) >= 0) {
        throw_runtime(env, "QuickJS main() 必须同步返回 JSON，不支持 Promise/async");
        JS_FreeValue(ctx, returned); JS_FreeValue(ctx, input_value); JS_FreeValue(ctx, global);
        goto cleanup;
    }
    JSValue json_object = JS_GetPropertyStr(ctx, global, "JSON");
    JSValue stringify = JS_GetPropertyStr(ctx, json_object, "stringify");
    JSValue result_args[1] = {returned};
    JSValue json_result = JS_Call(ctx, stringify, json_object, 1, result_args);
    JS_FreeValue(ctx, stringify);
    JS_FreeValue(ctx, json_object);
    JS_FreeValue(ctx, returned);
    JS_FreeValue(ctx, input_value);
    JS_FreeValue(ctx, global);
    if (JS_IsException(json_result)) {
        char *message = exception_text(ctx);
        throw_runtime(env, message != NULL ? message : "QuickJS 结果无法序列化");
        free(message);
        JS_FreeValue(ctx, json_result);
        goto cleanup;
    }
    if (JS_IsUndefined(json_result)) {
        JS_FreeValue(ctx, json_result);
        throw_runtime(env, "QuickJS main() 必须返回可序列化的 JSON 值");
        goto cleanup;
    }
    const char *json_text = JS_ToCString(ctx, json_result);
    if (json_text == NULL) {
        JS_FreeValue(ctx, json_result);
        throw_runtime(env, "QuickJS 结果编码失败");
        goto cleanup;
    }
    result_string = new_java_utf8(env, json_text);
    JS_FreeCString(ctx, json_text);
    JS_FreeValue(ctx, json_result);

cleanup:
    if (ctx != NULL) JS_FreeContext(ctx);
    if (runtime != NULL) JS_FreeRuntime(runtime);
    (*env)->DeleteGlobalRef(env, state.host);
    (*env)->DeleteLocalRef(env, thread);
    free((void *)source);
    free((void *)input);
    return result_string;
}
