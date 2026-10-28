// Google Maven (dl.google.com) is blocked in some environments, so this app
// module is compiled with the local Android SDK (aapt2, d8, apksigner) and
// kotlinc instead of the Android Gradle Plugin. The source layout is still a
// normal Android app module.

val junitConfig = configurations.create("junit")

dependencies {
    add("junit", "junit:junit:4.13.2")
}

val androidSdk = providers.provider { resolveAndroidSdk() }
val buildToolsDir = androidSdk.map { resolveBuildTools(it) }
val androidJar = androidSdk.map { File(it, "platforms/android-35/android.jar") }

tasks.register("assembleDebug") {
    group = "build"
    description = "Compile the app module and package a debug APK"
    val outApk = project.layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    outputs.file(outApk)
    doLast {
        val sdkJar = androidJar.get()
        val tools = buildToolsDir.get()
        require(sdkJar.isFile) { "Missing Android platform jar: $sdkJar" }
        val kotlinc = resolveKotlinc()
        val kotlinLib = File(kotlinc.parentFile.parentFile, "lib")
        val buildRoot = project.layout.buildDirectory.get().asFile
        val genDir = File(buildRoot, "gen")
        val classesDir = File(buildRoot, "classes")
        val dexDir = File(buildRoot, "dex")
        val intermediate = File(buildRoot, "intermediates")
        listOf(genDir, classesDir, dexDir, intermediate).forEach { dir ->
            dir.deleteRecursively()
            dir.mkdirs()
        }
        outApk.get().asFile.parentFile.mkdirs()

        val compiledRes = File(intermediate, "compiled-res.zip")
        val baseApk = File(intermediate, "base.apk")
        val classesJar = File(intermediate, "classes.jar")
        val unsignedApk = File(intermediate, "unsigned.apk")
        val alignedApk = File(intermediate, "aligned.apk")
        val keystore = File(intermediate, "debug.keystore")

        runTool(
            listOf(
                File(tools, "aapt2").absolutePath,
                "compile",
                "--dir",
                project.file("src/main/res").absolutePath,
                "-o",
                compiledRes.absolutePath
            )
        )
        runTool(
            listOf(
                File(tools, "aapt2").absolutePath,
                "link",
                "-o",
                baseApk.absolutePath,
                "-I",
                sdkJar.absolutePath,
                "--manifest",
                project.file("src/main/AndroidManifest.xml").absolutePath,
                "--java",
                genDir.absolutePath,
                "--min-sdk-version",
                "30",
                "--target-sdk-version",
                "35",
                compiledRes.absolutePath
            )
        )
        val rJava = genDir.walkTopDown().firstOrNull { it.name == "R.java" }
            ?: error("aapt2 did not generate R.java")
        runTool(
            listOf(
                "javac",
                "--release",
                "17",
                "-classpath",
                sdkJar.absolutePath,
                "-d",
                classesDir.absolutePath,
                rJava.absolutePath
            )
        )
        val kotlinClasspath = listOf(sdkJar, classesDir).joinToString(File.pathSeparator) { it.absolutePath }
        runTool(
            listOf(
                kotlinc.absolutePath,
                "-jvm-target",
                "17",
                "-classpath",
                kotlinClasspath,
                "-d",
                classesDir.absolutePath,
                project.file("src/main/java").absolutePath
            )
        )
        runTool(listOf("jar", "cf", classesJar.absolutePath, "-C", classesDir.absolutePath, "."))
        val stdlibJars = kotlinLib.listFiles { candidate ->
            candidate.isFile &&
                candidate.name.startsWith("kotlin-stdlib") &&
                candidate.name.endsWith(".jar") &&
                !candidate.name.contains("sources") &&
                !candidate.name.contains("-js") &&
                !candidate.name.contains("wasm")
        }?.sortedBy { it.name } ?: emptyList()
        require(stdlibJars.isNotEmpty()) { "kotlin-stdlib jar not found in $kotlinLib" }
        val d8Args = mutableListOf(
            File(tools, "d8").absolutePath,
            "--min-api",
            "30",
            "--lib",
            sdkJar.absolutePath,
            "--output",
            dexDir.absolutePath,
            classesJar.absolutePath
        )
        d8Args += stdlibJars.map { it.absolutePath }
        runTool(d8Args)
        baseApk.copyTo(unsignedApk, overwrite = true)
        val dexFile = File(dexDir, "classes.dex")
        require(dexFile.isFile) { "d8 did not produce classes.dex" }
        runTool(listOf("zip", "-j", "-u", unsignedApk.absolutePath, dexFile.absolutePath))
        runTool(
            listOf(
                File(tools, "zipalign").absolutePath,
                "-f",
                "-p",
                "4",
                unsignedApk.absolutePath,
                alignedApk.absolutePath
            )
        )
        if (!keystore.isFile) {
            runTool(
                listOf(
                    "keytool",
                    "-genkeypair",
                    "-keystore",
                    keystore.absolutePath,
                    "-storepass",
                    "android",
                    "-keypass",
                    "android",
                    "-alias",
                    "androiddebugkey",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "10000",
                    "-dname",
                    "CN=Android Debug,O=Android,C=US"
                )
            )
        }
        val signed = outApk.get().asFile
        if (signed.exists()) signed.delete()
        runTool(
            listOf(
                File(tools, "apksigner").absolutePath,
                "sign",
                "--ks",
                keystore.absolutePath,
                "--ks-key-alias",
                "androiddebugkey",
                "--ks-pass",
                "pass:android",
                "--key-pass",
                "pass:android",
                "--out",
                signed.absolutePath,
                alignedApk.absolutePath
            )
        )
        project.logger.lifecycle("Debug APK: ${signed.absolutePath}")
    }
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Run JVM unit tests for screenshot and Garmin window rules"
    val junitFiles = junitConfig
    doLast {
        val kotlinc = resolveKotlinc()
        val classesDir = project.layout.buildDirectory.get().asFile.resolve("test-classes")
        classesDir.deleteRecursively()
        classesDir.mkdirs()
        val classpath = junitFiles.files.joinToString(File.pathSeparator) { it.absolutePath }
        val sources = listOf(
            project.file("src/main/java/dev/juliansaratsola/micoachfitness/ScreenshotClassifier.kt"),
            project.file("src/main/java/dev/juliansaratsola/micoachfitness/GarminActivityWindow.kt"),
            project.file("src/test/java")
        )
        runTool(
            listOf(
                kotlinc.absolutePath,
                "-jvm-target",
                "17",
                "-classpath",
                classpath,
                "-d",
                classesDir.absolutePath
            ) + sources.map { it.absolutePath }
        )
        val testClasses = classesDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.class") }
            .map { file ->
                classesDir.toPath().relativize(file.toPath()).toString()
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }
            .toList()
        require(testClasses.isNotEmpty()) { "No unit test classes were compiled" }
        val kotlinLib = File(kotlinc.parentFile.parentFile, "lib")
        val stdlibJars = kotlinLib.listFiles { candidate ->
            candidate.isFile &&
                candidate.name.startsWith("kotlin-stdlib") &&
                candidate.name.endsWith(".jar") &&
                !candidate.name.contains("sources") &&
                !candidate.name.contains("-js") &&
                !candidate.name.contains("wasm")
        }?.toList() ?: emptyList()
        val runtime = (listOf(classesDir) + stdlibJars + junitFiles.files)
            .joinToString(File.pathSeparator) { it.absolutePath }
        runTool(listOf("java", "-cp", runtime, "org.junit.runner.JUnitCore") + testClasses)
    }
}

tasks.register("check") {
    dependsOn("testDebugUnitTest")
}

fun resolveAndroidSdk(): File {
    val fromEnv = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it) }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
    if (fromEnv != null) return fromEnv
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        val path = local.readLines()
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("=")
            ?.trim()
        if (!path.isNullOrEmpty() && File(path).isDirectory) return File(path)
    }
    error("Android SDK not found. Set ANDROID_HOME.")
}

fun resolveBuildTools(sdk: File): File {
    val tools = File(sdk, "build-tools")
    val preferred = File(tools, "35.0.0")
    if (preferred.isDirectory) return preferred
    return tools.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        ?: error("No build-tools installed under $tools")
}

fun resolveKotlinc(): File {
    val candidates = listOfNotNull(
        System.getenv("KOTLIN_HOME")?.let { File(it, "bin/kotlinc") },
        File("/usr/share/kotlinc/bin/kotlinc")
    )
    candidates.firstOrNull { it.canExecute() }?.let { return it }
    val dest = rootProject.layout.buildDirectory.get().asFile.resolve("kotlin-compiler")
    val bin = File(dest, "kotlinc/bin/kotlinc")
    if (!bin.canExecute()) {
        dest.mkdirs()
        val zip = File(dest, "kotlin-compiler-2.0.21.zip")
        val url = "https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip"
        runTool(listOf("curl", "-fsSL", "--retry", "3", "-o", zip.absolutePath, url))
        runTool(listOf("unzip", "-q", "-o", zip.absolutePath, "-d", dest.absolutePath))
    }
    if (!bin.canExecute()) {
        error("kotlinc not found. Install Kotlin, set KOTLIN_HOME, or allow the GitHub download fallback.")
    }
    return bin
}

fun runTool(args: List<String>) {
    val process = ProcessBuilder(args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Command failed ($exitCode): ${args.joinToString(" ")}\n$output")
    }
}
