version = "0.1.7"
description = "Identifies Pokétwo spawn images on-device with ONNX and fills in your catch command when you send a message."

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.aliucord.plugin)
}

android {
    namespace = "dev.khopdi.pokemoncatch"
}

dependencies {
    compileOnly(libs.discord)
    compileOnly(libs.kotlin.stdlib)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}

aliucord {
    author("Khopdi Todo", 0L)
    deploy.set(true)
}
