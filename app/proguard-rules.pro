-keepattributes Signature

# Gson
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

# OkHttp
-keep,allowobfuscation,allowshrinking class okhttp3.RequestBody
-keep,allowobfuscation,allowshrinking class okhttp3.ResponseBody

#Retrofit
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

# Models
-keep class com.onekey.updater.data.** { *; }

# 内嵌 MCP 服务端对外返回的 DTO 也必须保留字段名。
# 这些类不走 Retrofit，而是在 McpServer 里用 Gson 手动序列化后作为 JSON-RPC 的 content 返回；
# 上面的 data.** 规则覆盖不到 util.mcp，结果 release 包里字段名被混淆成 a/b/c ——
# 实测 get_root_status 返回的是 {"a":true,"b":"..."}，客户端按 available/detail 解析必然失败。
# debug 包不混淆，所以这个问题只在正式包里出现。
-keep class com.onekey.updater.util.mcp.** { *; }
