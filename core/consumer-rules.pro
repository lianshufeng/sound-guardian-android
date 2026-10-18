# sherpa-onnx JNI resolves the concrete typed callback method by name/signature.
-keep class com.soundguardian.core.SherpaSpeechSynthesizer$* {
    java.lang.Integer invoke(float[]);
}
