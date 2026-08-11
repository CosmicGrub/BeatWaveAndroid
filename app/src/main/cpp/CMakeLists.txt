cmake_minimum_required(VERSION 3.22.1)
project(beatwave_audio)

# Oboe is supplied as a prebuilt Prefab package via the
# com.google.oboe:oboe Gradle dependency (see app/build.gradle.kts).
find_package(oboe REQUIRED CONFIG)

add_library(beatwave_audio SHARED
        audio_engine_jni.cpp
        AudioEngine.cpp
        WavDecoder.cpp
        SampleBank.cpp
        ScoreBuilder.cpp
        MixEngine.cpp
)

target_link_libraries(beatwave_audio
        oboe::oboe
        log
        android
)

target_compile_features(beatwave_audio PRIVATE cxx_std_17)
