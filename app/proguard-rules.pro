# JNI: native methods are looked up by class + method name (Java_com_voicex_app_engine_TranscribeNative_*)
-keep class com.voicex.app.engine.TranscribeNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Services / receivers referenced from the manifest are kept by AAPT2 rules; the accessibility service
# config XML refers to the service by name too.
-keep class com.voicex.app.dot.DotAccessibilityService { *; }
