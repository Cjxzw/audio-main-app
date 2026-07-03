# ==================== LangChain4j 反射兼容性 ====================
# 保留 LangChain4j 所有类（工具调用依赖反射）
-keep class dev.langchain4j.** { *; }
-keepclassmembers class dev.langchain4j.** {
    public *;
    private *;
}

# 枚举保护：避免 values()/valueOf() 被 R8 混淆导致崩溃
-keep enum dev.langchain4j.agent.tool.ReturnBehavior { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ==================== 工具注解保护 ====================
-keep @dev.langchain4j.agent.tool.Tool class * { *; }
-keepclassmembers class * {
    @dev.langchain4j.agent.tool.P *;
    @dev.langchain4j.agent.tool.P(<fields>) *;
}

# ==================== AI Services 动态代理 ====================
-keep interface com.agent.voiceassistant.agent.Assistant { *; }
-keep class com.agent.voiceassistant.agent.Assistant { *; }
-keep @dev.langchain4j.service.SystemMessage class * { *; }
-keep @dev.langchain4j.service.UserMessage class * { *; }
-keep @dev.langchain4j.service.V class * { *; }

# ==================== Kotlin 反射 / 序列化 ====================
-keep class kotlin.reflect.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ==================== Sherpa-ONNX 原生接口 ====================
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    public *;
    protected *;
}

# ==================== Retrofit / OkHttp ====================
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ==================== 构建配置 ====================
-keep class com.agent.voiceassistant.BuildConfig { *; }
-keep class com.agent.voiceassistant.** { *; }
