import com.unciv.build.BuildConfig
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.register

plugins {
    kotlin("jvm")
    application
}

val assetsDir = file("../android/assets")
val discordDir = file("../discord/src")
val headlessMainClassName = "com.unciv.app.desktop.MeasurementHeadlessRunner"
val headlessJarName = "UncivHeadless.jar"

sourceSets.main {
    resources.srcDir(assetsDir)
    resources.srcDir(discordDir)
}

application {
    mainClass.set("com.unciv.app.desktop.DesktopLauncher")
}

if (project.hasProperty("release")) {
    application.applicationDefaultJvmArgs = listOf("-Drelease=true")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx.backends.lwjgl3)
    implementation(libs.gdx.platform)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.text)
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the desktop application."
    mainClass.set(application.mainClass)
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = assetsDir
}

tasks.register<JavaExec>("debug") {
    group = "application"
    description = "Runs the desktop application in debug mode."
    mainClass.set(application.mainClass)
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = assetsDir
    jvmArgs = listOf("-Ddebug=true")
}

tasks.register<Copy>("copyAndroidNatives") {
    from(configurations.runtimeClasspath)
    into("libs")
}

tasks.register<Exec>("dist") {
    dependsOn("jar")
    workingDir = file(".")
    commandLine("java", "-jar", "packr.jar", "packr.json")
}

tasks.register<JavaExec>("runHeadlessMeasurement") {
    dependsOn(tasks.getByName("classes"))
    mainClass.set(headlessMainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = assetsDir
    isIgnoreExitValue = true
}

tasks.register<Jar>("headlessDist") {
    dependsOn(tasks.getByName("classes"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(files(sourceSets.main.get().output.resourcesDir))
    from(files(sourceSets.main.get().output.classesDirs))
    from({
        (
            configurations.runtimeClasspath.get().resolve()
            + configurations.compileClasspath.get().resolve()
        ).map { if (it.isDirectory) it else zipTree(it) }
    })
    from(files(assetsDir))
    exclude("mods", "SaveFiles", "MultiplayerFiles", "GameSettings.json", "lasterror.txt")
    from(files(discordDir))
    archiveFileName.set(headlessJarName)

    manifest {
        attributes(mapOf("Main-Class" to headlessMainClassName, "Specification-Version" to BuildConfig.appVersion))
    }
}

tasks.register<Zip>("chatHeadlessPack") {
    dependsOn(tasks.getByName("headlessDist"))
    archiveFileName.set("UncivHeadlessChatPack.zip")
    destinationDirectory.set(file("$rootDir/build/chat-headless"))

    from(file("$buildDir/libs/$headlessJarName"))
    from(file("$assetsDir/jsons")) {
        into("jsons")
    }
    from(file("$rootDir/measurement-config.sample.json")) {
        rename { "measurement-config.json" }
    }
    from(file("$rootDir/neural-overlays")) {
        into("neural-overlays")
    }
    from(file("$rootDir/README_HEADLESS_CHAT.md"))
    from(file("$rootDir/scripts/run-headless.sh"))
    from(file("$rootDir/scripts/run-headless.bat"))
    from(file("$rootDir/scripts/train_headless_value_model.py"))
}

tasks.register<Zip>("zipLinuxFilesForJar") {
    archiveFileName.set("linuxFilesForJar.zip")
    from(file("linuxFilesForJar"))
    destinationDirectory.set(deployFolder)
}
