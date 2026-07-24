import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.GZIPInputStream
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
        versionCode = 34
        versionName = "0.7.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti")
            }
        }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        }
    }
}

kotlin {
    jvmToolchain(17)
}

val bumpMeshCommit = "a6ac179149b8a17c71a9469dd4cb6f866c0c01d1"
val threeVersion = "r170"
val fflateVersion = "0.8.2"
val meshStepVersion = "0.1.0"
val bumpMeshAssetFormat = 2
val bumpMeshOutput = layout.projectDirectory.dir("src/main/assets/bumpmesh")
val bumpMeshAndroidBridge = layout.projectDirectory.file("src/main/bumpmesh/android-bridge.js")

val prepareBumpMeshAssets by tasks.registering {
    group = "build setup"
    description = "Downloads and prepares the pinned offline BumpMesh workspace"
    inputs.property("bumpMeshCommit", bumpMeshCommit)
    inputs.property("threeVersion", threeVersion)
    inputs.property("fflateVersion", fflateVersion)
    inputs.property("meshStepVersion", meshStepVersion)
    inputs.property("bumpMeshAssetFormat", bumpMeshAssetFormat)
    inputs.file(bumpMeshAndroidBridge)
    outputs.file(bumpMeshOutput.file(".source-version"))

    doLast {
        val outputDirectory = bumpMeshOutput.asFile
        val marker = File(outputDirectory, ".source-version")
        val expectedMarker = buildString {
            appendLine("format=$bumpMeshAssetFormat")
            appendLine("BumpMesh=$bumpMeshCommit")
            appendLine("three=$threeVersion")
            appendLine("fflate=$fflateVersion")
            appendLine("meshstep=$meshStepVersion")
        }
        if (
            marker.isFile && marker.readText() == expectedMarker &&
            File(outputDirectory, "index.html").isFile &&
            File(outputDirectory, "android-bridge.js").isFile
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

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareBumpMeshAssets)
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
