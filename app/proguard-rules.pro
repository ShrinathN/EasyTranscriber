# sherpa-onnx's native code reads and writes these Kotlin classes' fields by name (JNI).
-keep class com.k2fsa.sherpa.onnx.** { *; }
# ONNX Runtime's JNI layer looks up its Java classes and methods by name.
-keep class ai.onnxruntime.** { *; }

# commons-compress: only tar + bzip2 are used; its optional codecs (xz, zstd, brotli, ...) are absent.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
