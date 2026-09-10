import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tomppi.enderslicer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tomppi.enderslicercura"
        minSdk = 29
        targetSdk = 36
        versionCode = 37
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
        jniLibs {
            useLegacyPackaging = true
            // The staged Blender engine is linked with -g: ~91% of its 1.37 GB is
            // DWARF (.debug_info/.debug_str/...), which no runtime path reads. The
            // trimBlenderEngine task removes those sections in place - the dynamic
            // and JNI symbols the app enters through are untouched - and AGP must
            // not strip anything else from it.
            keepDebugSymbols += setOf("**/libblender_exec.so")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

val curaEngineExecutable = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libcuraengine_exec.so")

val verifyCuraEngineExecutable by tasks.registering {
    group = "verification"
    description = "Fails unless the packaged CuraEngine executable is a non-empty ARM64 ELF"
    // Keep this task uncached instead of declaring a mandatory input: Gradle
    // would otherwise emit a generic missing-input error before the actionable
    // prerequisite command below can be shown.
    outputs.upToDateWhen { false }

    doLast {
        val executable = curaEngineExecutable.asFile
        check(executable.isFile && executable.length() > 0L) {
            "CuraEngine ARM64 is missing. Run scripts/build-curaengine-android.sh before Gradle assembly."
        }
        val header = executable.inputStream().use { input -> input.readNBytes(20) }
        check(header.size >= 20) { "CuraEngine package is too small to be a valid ELF" }
        check(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
            "CuraEngine package is not an ELF executable"
        }
        check(header[4] == 2.toByte() && header[5] == 1.toByte()) {
            "CuraEngine package must be a 64-bit little-endian ELF"
        }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        check(machine == 183) { "CuraEngine package has ELF machine $machine; expected AArch64 (183)" }
    }
}

val prusaEngineExecutable = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libprusa_slicer_exec.so")

val verifyPrusaEngineExecutable by tasks.registering {
    group = "verification"
    description = "Fails unless the packaged PrusaSlicer executable is a non-empty static ARM64 ELF"
    outputs.upToDateWhen { false }

    doLast {
        val executable = prusaEngineExecutable.asFile
        check(executable.isFile && executable.length() > 0L) {
            "PrusaSlicer ARM64 is missing. Run scripts/fetch-prusa-engine-android.sh before Gradle assembly."
        }
        val header = executable.inputStream().use { input -> input.readNBytes(20) }
        check(header.size >= 20) { "PrusaSlicer package is too small to be a valid ELF" }
        check(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
            "PrusaSlicer package is not an ELF executable"
        }
        check(header[4] == 2.toByte() && header[5] == 1.toByte()) {
            "PrusaSlicer package must be a 64-bit little-endian ELF"
        }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        check(machine == 183) { "PrusaSlicer package has ELF machine $machine; expected AArch64 (183)" }
    }
}

val verifyDebugApkContents by tasks.registering {
    group = "verification"
    description = "Builds the debug APK and verifies that CuraEngine is packaged"
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apk.isFile && apk.length() > 0L) { "Debug APK was not created" }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("lib/arm64-v8a/libcuraengine_exec.so")
            check(entry != null && entry.size > 0L) {
                "Debug APK does not contain the ARM64 CuraEngine executable"
            }
        }
    }
}

val verifyDebugApkPrusaContents by tasks.registering {
    group = "verification"
    description = "Builds the debug APK and verifies that PrusaSlicer and its resources are packaged"
    dependsOn("verifyDebugApkContents")
    doLast {
        check(prusaEngineExecutable.asFile.isFile) {
            "PrusaSlicer ARM64 is missing. Run scripts/fetch-prusa-engine-android.sh before assembly."
        }
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apk.isFile && apk.length() > 0L) { "Debug APK was not created" }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("lib/arm64-v8a/libprusa_slicer_exec.so")
            check(entry != null && entry.size > 0L) {
                "Debug APK does not contain the ARM64 PrusaSlicer executable"
            }
            val resources = zip.getEntry("assets/prusa/resources/profiles/Anker.ini")
            check(resources != null && resources.size > 0L) {
                "Debug APK does not contain the PrusaSlicer resources"
            }
        }
    }
}

fun localPropertiesSdkDir(): File? {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return null
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    return properties.getProperty("sdk.dir")?.let { File(it) }
}

/** The NDK the engine blobs are built with; newer ones also work. */
val pinnedNdkVersion = "28.2.13676358"

fun newestNdk(sdkRoot: File?): File? {
    val ndkRoot = sdkRoot?.let { File(it, "ndk") } ?: return null
    return ndkRoot.resolve(pinnedNdkVersion).takeIf { it.isDirectory }
        ?: ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

val ndkDirectory: File by lazy {
    val fromEnvironment = listOfNotNull(
        System.getenv("ANDROID_NDK_HOME")?.let { File(it) },
        System.getenv("ANDROID_NDK_ROOT")?.let { File(it) },
    ).firstOrNull { it.isDirectory }
    if (fromEnvironment != null) {
        fromEnvironment
    } else {
        listOfNotNull(
            newestNdk(System.getenv("ANDROID_HOME")?.let { File(it) }),
            newestNdk(System.getenv("ANDROID_SDK_ROOT")?.let { File(it) }),
            newestNdk(localPropertiesSdkDir()),
        ).firstOrNull { it.isDirectory }
            ?: error("No Android NDK found; set ANDROID_NDK_HOME or install one for this SDK")
    }
}

val blenderEngineBlob = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libblender_exec.so")

/** Strips DWARF debug sections from the staged Blender engine (-g linked). */
val trimBlenderEngine by tasks.registering {
    group = "build"
    description = "Removes DWARF debug info from the staged Blender engine (no functional change)"
    outputs.upToDateWhen { false }

    doLast {
        val blob = blenderEngineBlob.asFile
        if (!blob.isFile || blob.length() == 0L) {
            logger.lifecycle("Blender engine is not staged; skipping the debug-info trim")
            return@doLast
        }

        val hostDir = sequenceOf("windows-x86_64", "linux-x86_64", "darwin-x86_64")
            .map { File(ndkDirectory, "toolchains/llvm/prebuilt/$it/bin") }
            .firstOrNull { it.isDirectory }
            ?: error("Android NDK toolchain not found under $ndkDirectory")
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val objcopy = File(hostDir, "llvm-objcopy$suffix")
        val readelf = File(hostDir, "llvm-readelf$suffix")
        val nm = File(hostDir, "llvm-nm$suffix")
        check(objcopy.isFile && readelf.isFile && nm.isFile) {
            "llvm-objcopy/llvm-readelf/llvm-nm missing in $hostDir"
        }

        fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) {
                "command failed: ${command.joinToString(" ")}\n$output"
            }
            return output
        }

        val sections = run(readelf.absolutePath, "-S", blob.absolutePath)
        if (!sections.lineSequence().any { it.contains(".debug_info") }) {
            logger.lifecycle("Blender engine already trimmed (${blob.length() / 1048576} MB)")
            return@doLast
        }

        val jniPrefix = "Java_com_tomppi_enderslicer_nativebridge_BlenderBridge_"
        val before = blob.length()
        val symbolsBefore = run(nm.absolutePath, "-D", "--defined-only", blob.absolutePath).lineSequence().count { it.isNotBlank() }
        val jniBefore = run(nm.absolutePath, "-D", "--defined-only", blob.absolutePath).lineSequence().count { it.contains(jniPrefix) }

        run(objcopy.absolutePath, "--strip-debug", blob.absolutePath)

        val symbolsAfter = run(nm.absolutePath, "-D", "--defined-only", blob.absolutePath).lineSequence().count { it.isNotBlank() }
        val jniAfter = run(nm.absolutePath, "-D", "--defined-only", blob.absolutePath).lineSequence().count { it.contains(jniPrefix) }
        check(symbolsAfter == symbolsBefore && jniAfter == jniBefore && jniAfter > 0) {
            "Blender engine trim changed the dynamic symbol table ($symbolsBefore -> $symbolsAfter, JNI $jniBefore -> $jniAfter)"
        }

        logger.lifecycle(
            "Blender engine trimmed: ${before / 1048576} MB -> ${blob.length() / 1048576} MB " +
                "(dynamic symbols $symbolsAfter, JNI ${jniAfter})"
        )
    }
}

val blenderAssetsDir = layout.projectDirectory.dir("src/main/assets/blender")

/**
 * Drops data from the staged Blender assets that can never be used on Android:
 *  - scripts/addons/cycles/lib, the CUDA kernels (cubin, ptx, hipfb, fatbin): CUDA/PTX/OptiX/HIP
 *    kernels for desktop NVIDIA and AMD GPUs. Android has no CUDA/HIP runtime, so
 *    Cycles keeps working through its CPU kernels.
 *  - python/lib/python3.11/{venv,ensurepip} and numpy test suites: developer
 *    scaffolding that nothing in the embedded interpreter runs.
 * Set -PblenderKeepGpuKernels=true to keep the GPU kernels.
 */
val pruneBlenderAssets = tasks.register("pruneBlenderAssets") {
    group = "build"
    description = "Drops Blender assets that are unusable on Android (GPU kernels, dev scaffolding)"
    outputs.upToDateWhen { false }

    doLast {
        val assets = blenderAssetsDir.asFile
        if (!assets.isDirectory) {
            logger.lifecycle("Blender assets are not staged; skipping the asset prune")
            return@doLast
        }

        val keepGpuKernels = (project.findProperty("blenderKeepGpuKernels") as String?)?.toBoolean() ?: false
        val targets = mutableListOf<File>()
        if (!keepGpuKernels) {
            val cyclesLib = File(assets, "scripts/addons/cycles/lib")
            if (cyclesLib.isDirectory) {
                targets += cyclesLib.listFiles { file: File ->
                    file.isFile && file.extension.lowercase() in setOf("cubin", "ptx", "hipfb", "fatbin")
                }?.toList().orEmpty()
            }
        }
        targets += listOf(
            File(assets, "python/lib/python3.11/venv"),
            File(assets, "python/lib/python3.11/ensurepip"),
        ).filter { it.exists() }
        val numpyTests = File(assets, "python/lib/python3.11/site-packages/numpy")
        if (numpyTests.isDirectory) {
            numpyTests.walkTopDown().filter { it.isDirectory && it.name == "tests" }.forEach { targets += it }
        }

        var freed = 0L
        var removed = 0
        for (target in targets.distinct().sortedByDescending { it.path.length }) {
            if (!target.exists()) continue
            freed += if (target.isDirectory) {
                target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                target.length()
            }
            if (target.isDirectory) target.deleteRecursively() else target.delete()
            removed++
        }

        if (removed == 0) {
            logger.lifecycle("Blender assets already pruned")
        } else {
            logger.lifecycle("Blender assets pruned: ${removed} entries, ${freed / 1048576} MB freed")
        }
    }
}

tasks.named("preBuild") { dependsOn(trimBlenderEngine, pruneBlenderAssets) }

val verifyDebugApkBlenderContents by tasks.registering {
    group = "verification"
    description = "Builds the debug APK and verifies the Blender MCP engine lib + assets are packaged"
    dependsOn("verifyDebugApkPrusaContents")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apk.isFile && apk.length() > 0L) { "Debug APK was not created" }
        ZipFile(apk).use { zip ->
            val engine = zip.getEntry("lib/arm64-v8a/libblender_exec.so")
            check(engine != null && engine.size > 40L * 1024 * 1024) {
                "Debug APK does not contain the ARM64 Blender engine (" + (engine?.size ?: 0L) + " bytes)"
            }
            val pythonCount = zip.entries().asSequence().count { it.name.startsWith("assets/blender/python/lib/python3.11/") }
            check(pythonCount > 1000) {
                "Debug APK does not contain the Blender python assets (found $pythonCount entries)"
            }
            val addon = zip.getEntry("assets/blender/scripts/startup/start_blender_mcp.py")
            check(addon != null && addon.size > 0L) {
                "Debug APK does not contain the Blender MCP addon"
            }
        }
    }
}
val bumpMeshCommit = "a6ac179149b8a17c71a9469dd4cb6f866c0c01d1"
val threeVersion = "r170"
val fflateVersion = "0.8.2"
val meshStepVersion = "0.1.0"
val bumpMeshAssetFormat = 2
val bumpMeshOutput = layout.projectDirectory.dir("src/main/assets/bumpmesh")
val bumpMeshAndroidBridge = layout.projectDirectory.file("src/main/bumpmesh/android-bridge.js")
val bumpMeshRequiredRuntimePaths = listOf(
    ".source-version",
    "index.html",
    "style.css",
    "LICENSE",
    "android-bridge.js",
    "js/main.js",
    "js/stepWorker.js",
    "js/threeCompat.js",
    "vendor/three/build/three.module.js",
    "vendor/three/LICENSE",
    "vendor/fflate/esm/browser.js",
    "vendor/fflate/LICENSE",
    "vendor/meshstep/dist/index.js",
    "vendor/meshstep/src/index.ts",
    "vendor/meshstep/LICENSE",
)
val bumpMeshRequiredRuntimeFiles = bumpMeshRequiredRuntimePaths.map { relativePath ->
    bumpMeshOutput.file(relativePath)
}
val bumpMeshExpectedMarker = buildString {
    appendLine("format=$bumpMeshAssetFormat")
    appendLine("BumpMesh=$bumpMeshCommit")
    appendLine("three=$threeVersion")
    appendLine("fflate=$fflateVersion")
    appendLine("meshstep=$meshStepVersion")
}

val prepareBumpMeshAssets by tasks.registering {
    group = "build setup"
    description = "Downloads and prepares the pinned offline BumpMesh workspace"
    inputs.property("bumpMeshCommit", bumpMeshCommit)
    inputs.property("threeVersion", threeVersion)
    inputs.property("fflateVersion", fflateVersion)
    inputs.property("meshStepVersion", meshStepVersion)
    inputs.property("bumpMeshAssetFormat", bumpMeshAssetFormat)
    inputs.file(bumpMeshAndroidBridge)
    outputs.files(bumpMeshRequiredRuntimeFiles)
    outputs.upToDateWhen {
        val marker = bumpMeshOutput.file(".source-version").asFile
        marker.isFile &&
            marker.readText() == bumpMeshExpectedMarker &&
            bumpMeshRequiredRuntimeFiles.all { runtimeFile ->
                runtimeFile.asFile.isFile && runtimeFile.asFile.length() > 0L
            }
    }

    doLast {
        val outputDirectory = bumpMeshOutput.asFile
        val marker = File(outputDirectory, ".source-version")
        val expectedMarker = bumpMeshExpectedMarker
        if (
            marker.isFile && marker.readText() == expectedMarker &&
            bumpMeshRequiredRuntimeFiles.all { runtimeFile ->
                runtimeFile.asFile.isFile && runtimeFile.asFile.length() > 0L
            }
        ) {
            return@doLast
        }

        fun download(url: String): ByteArray {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "enderslicercura-build")
            }
            return connection.getInputStream().buffered().use { it.readBytes() }
        }

        fun safeTarget(destination: File, relative: String, archiveName: String): File {
            val root = destination.canonicalFile
            val target = File(destination, relative).canonicalFile
            check(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
                "Unsafe archive entry: $archiveName"
            }
            return target
        }

        fun extractZip(
            archive: ByteArray,
            destination: File,
            include: (String) -> Boolean,
        ) {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val firstSlash = entry.name.indexOf('/')
                    if (firstSlash < 0) continue
                    val relative = entry.name.substring(firstSlash + 1)
                    if (relative.isBlank() || !include(relative)) continue
                    val target = safeTarget(destination, relative, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> zip.copyTo(output) }
                    }
                }
            }
        }

        fun InputStream.readTarBlock(buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val count = read(buffer, offset, buffer.size - offset)
                if (count < 0) break
                offset += count
            }
            return offset
        }

        fun InputStream.copyExactly(output: java.io.OutputStream?, byteCount: Long) {
            var remaining = byteCount
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Unexpected end of TAR archive" }
                output?.write(buffer, 0, count)
                remaining -= count
            }
        }

        fun tarText(header: ByteArray, offset: Int, length: Int): String {
            val end = (offset until offset + length).firstOrNull { header[it].toInt() == 0 } ?: offset + length
            return header.copyOfRange(offset, end).toString(Charsets.US_ASCII).trim()
        }

        fun extractTarGz(
            archive: ByteArray,
            destination: File,
            include: (String) -> Boolean,
        ) {
            GZIPInputStream(archive.inputStream().buffered()).use { tar ->
                val header = ByteArray(512)
                while (true) {
                    val headerBytes = tar.readTarBlock(header)
                    if (headerBytes == 0) break
                    check(headerBytes == header.size) { "Truncated TAR header" }
                    if (header.all { it.toInt() == 0 }) break

                    val name = tarText(header, 0, 100)
                    val prefix = tarText(header, 345, 155)
                    val archiveName = if (prefix.isBlank()) name else "$prefix/$name"
                    val relative = archiveName.removePrefix("package/")
                    val sizeText = tarText(header, 124, 12).trim().trimStart('0')
                    val size = if (sizeText.isBlank()) 0L else sizeText.toLong(8)
                    val type = header[156].toInt().toChar()
                    val selected = relative.isNotBlank() && include(relative)

                    if (type == '5') {
                        if (selected) safeTarget(destination, relative, archiveName).mkdirs()
                    } else {
                        val target = if (selected) safeTarget(destination, relative, archiveName) else null
                        target?.parentFile?.mkdirs()
                        target?.outputStream()?.buffered()?.use { output -> tar.copyExactly(output, size) }
                            ?: tar.copyExactly(null, size)
                    }

                    val padding = (512L - size % 512L) % 512L
                    if (padding > 0) tar.copyExactly(null, padding)
                }
            }
        }

        val staging = layout.buildDirectory.dir("bumpmesh-assets-staging").get().asFile
        project.delete(staging)
        staging.mkdirs()

        val bumpMeshStage = File(staging, "bumpmesh")
        extractZip(
            archive = download("https://github.com/CNCKitchen/stlTexturizer/archive/$bumpMeshCommit.zip"),
            destination = bumpMeshStage,
        ) { relative ->
            relative == "index.html" ||
                relative == "style.css" ||
                relative == "logo.png" ||
                relative == "LICENSE" ||
                relative.startsWith("js/") ||
                relative.startsWith("textures/")
        }

        val threeStage = File(bumpMeshStage, "vendor/three")
        extractZip(
            archive = download("https://github.com/mrdoob/three.js/archive/refs/tags/$threeVersion.zip"),
            destination = threeStage,
        ) { relative ->
            relative == "LICENSE" ||
                relative == "build/three.module.js" ||
                relative.startsWith("examples/jsm/")
        }

        val fflateStage = File(bumpMeshStage, "vendor/fflate")
        extractTarGz(
            archive = download("https://registry.npmjs.org/fflate/-/fflate-$fflateVersion.tgz"),
            destination = fflateStage,
        ) { relative ->
            relative == "esm/browser.js" ||
                relative == "LICENSE" ||
                relative == "README.md" ||
                relative == "package.json"
        }

        val meshStepStage = File(bumpMeshStage, "vendor/meshstep")
        extractTarGz(
            archive = download("https://registry.npmjs.org/meshstep/-/meshstep-$meshStepVersion.tgz"),
            destination = meshStepStage,
        ) { relative ->
            relative == "LICENSE" ||
                relative == "README.md" ||
                relative == "package.json" ||
                relative.startsWith("dist/") ||
                relative.startsWith("src/")
        }

        val indexFile = File(bumpMeshStage, "index.html")
        check(indexFile.isFile) { "Pinned BumpMesh archive did not contain index.html" }
        var index = indexFile.readText()
        index = index
            .replace(
                "https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js",
                "./vendor/three/build/three.module.js",
            )
            .replace(
                "https://cdn.jsdelivr.net/npm/three@0.170.0/examples/jsm/",
                "./vendor/three/examples/jsm/",
            )
            .replace(
                "https://cdn.jsdelivr.net/npm/fflate@0.8.2/esm/browser.js",
                "./vendor/fflate/esm/browser.js",
            )
            .replace(Regex("\\s*<link rel=\"preconnect\" href=\"https://cdn\\.jsdelivr\\.net\" crossorigin>"), "")
            .replace(Regex("\\s*<link rel=\"modulepreload\" href=\"https://cdn\\.jsdelivr\\.net/npm/three@0\\.170\\.0/build/three\\.module\\.js\">"), "")
            .replace(
                "  <script type=\"module\" src=\"js/main.js\"></script>",
                "  <script src=\"android-bridge.js\"></script>\n  <script type=\"module\" src=\"js/main.js\"></script>",
            )
        check("./vendor/three/build/three.module.js" in index) { "Unable to localize BumpMesh's Three.js import map" }
        check("android-bridge.js" in index) { "Unable to add the Android BumpMesh bridge" }
        indexFile.writeText(index)

        val stepWorker = File(bumpMeshStage, "js/stepWorker.js")
        check(stepWorker.isFile) { "Pinned BumpMesh archive did not contain stepWorker.js" }
        val patchedStepWorker = stepWorker.readText().replace(
            "https://cdn.jsdelivr.net/npm/meshstep@0.1.0/+esm",
            "../vendor/meshstep/dist/index.js",
        )
        check("../vendor/meshstep/dist/index.js" in patchedStepWorker) { "Unable to localize meshStep" }
        stepWorker.writeText(patchedStepWorker)

        val threeCompat = File(bumpMeshStage, "js/threeCompat.js")
        check(threeCompat.isFile) { "Pinned BumpMesh archive did not contain threeCompat.js" }
        val patchedThreeCompat = threeCompat.readText().replace(
            "https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js",
            "../vendor/three/build/three.module.js",
        )
        check("../vendor/three/build/three.module.js" in patchedThreeCompat) {
            "Unable to localize the BumpMesh worker Three.js fallback"
        }
        threeCompat.writeText(patchedThreeCompat)

        bumpMeshAndroidBridge.asFile.copyTo(File(bumpMeshStage, "android-bridge.js"), overwrite = true)
        File(bumpMeshStage, ".source-version").writeText(expectedMarker)

        project.delete(outputDirectory)
        outputDirectory.parentFile?.mkdirs()
        check(bumpMeshStage.renameTo(outputDirectory)) { "Unable to install prepared BumpMesh assets" }
        project.delete(staging)
    }
}

tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn(prepareBumpMeshAssets)
}

tasks.matching { it.name.startsWith("assemble") || it.name == "bundleDebug" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyCuraEngineExecutable)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.webkit:webkit:1.16.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
