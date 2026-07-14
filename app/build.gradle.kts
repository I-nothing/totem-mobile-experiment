plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "edu.whu.tmdb"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.whu.tmdb"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.register<JavaExec>("runMain") {
    dependsOn("compileDebugJavaWithJavac")
    standardInput = System.`in`
    systemProperty("file.encoding", "UTF-8")
    val androidJar = "${android.sdkDirectory}/platforms/android-${android.compileSdk}/android.jar"
    val runtimeClasspath: FileCollection = configurations.getByName("debugRuntimeClasspath")
    classpath = files(
        "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
        "libs/jsqlparser-4.6-SNAPSHOT.jar",
        androidJar
    ) + runtimeClasspath
    mainClass.set("edu.whu.tmdb.Main")
}

tasks.register<JavaExec>("runTest") {
    dependsOn("compileDebugJavaWithJavac")
    systemProperty("file.encoding", "UTF-8")
    val androidJar = "${android.sdkDirectory}/platforms/android-${android.compileSdk}/android.jar"
    val runtimeClasspath: FileCollection = configurations.getByName("debugRuntimeClasspath")
    classpath = files(
        "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
        "libs/jsqlparser-4.6-SNAPSHOT.jar",
        androidJar
    ) + runtimeClasspath
    mainClass.set("edu.whu.tmdb.CrossClassQueryTest")
}

tasks.register<JavaExec>("runRegressionTest") {
    dependsOn("compileDebugJavaWithJavac")
    systemProperty("file.encoding", "UTF-8")
    val androidJar = "${android.sdkDirectory}/platforms/android-${android.compileSdk}/android.jar"
    val runtimeClasspath: FileCollection = configurations.getByName("debugRuntimeClasspath")
    classpath = files(
        "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
        "libs/jsqlparser-4.6-SNAPSHOT.jar",
        androidJar
    ) + runtimeClasspath
    mainClass.set("edu.whu.tmdb.RegressionTest")
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(files("libs/jsqlparser-4.6-SNAPSHOT.jar"))
    implementation("com.alibaba.fastjson2:fastjson2-kotlin:2.0.50") {
        exclude(group = "com.alibaba.fastjson2", module = "fastjson2")
    }
    implementation("com.alibaba.fastjson2:fastjson2:2.0.35.android4")
    implementation("org.scala-lang:scala-library:2.12.8")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.graphhopper:graphhopper-map-matching-core:0.9.0")
    implementation("com.graphhopper:graphhopper-core:0.10.3")
    implementation("com.graphhopper:graphhopper-reader-osm:0.10.3")
    implementation("me.lemire.integercompression:JavaFastPFOR:0.1.11")
    implementation("com.github.davidmoten:geo:0.7.1")
    implementation("com.github.davidmoten:rtree:0.10")
    implementation("com.google.code.gson:gson:2.8.5")
}