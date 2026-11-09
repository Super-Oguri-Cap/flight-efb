-keepattributes SourceFile,LineNumberTable

# 网页桥：@JavascriptInterface 的方法名被 R8 改名后，网页里的
# Android.exportJson(...) 就找不到方法 —— 表现为 debug 正常、release 桥静默失效。
-keepclassmembers class com.oguricap.efb.ui.tools.ToolsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.oguricap.efb.ui.tools.ToolsBridge { *; }
