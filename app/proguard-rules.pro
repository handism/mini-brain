-keep class com.google.ai.edge.** { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.ai.edge.**
-dontwarn com.google.mediapipe.**

# ONNX Runtime の AAR には consumer ProGuard ルールが無い。JNI (libonnxruntime4j_jni.so) が
# FindClass("ai/onnxruntime/TensorInfo") などでクラス名・コンストラクタを直接引くため、
# 難読化されると埋め込み推論の初回で native abort する（v1.0.1 の実機クラッシュ）
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
