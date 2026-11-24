plugins {
    java
    application
}

group = "com.yuaworks.voicestandee"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("net.dv8tion:JDA:6.7.0") {
        // opus-java only decodes/encodes audio, which this project never does. tink is needed: it decrypts incoming voice packets.
        exclude(module = "opus-java")
    }
    // Discord's end-to-end encryption for voice (DAVE): the part that lets the bot join a voice channel at all
    implementation("club.minnced:jdave-api:0.1.8")
    runtimeOnly("club.minnced:jdave-native-linux-x86-64:0.1.8")
    runtimeOnly("club.minnced:jdave-native-win-x86-64:0.1.8")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val jvmFlags = listOf(
    "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
    // When someone leaves or joins, the voice encryption keys change; for a few seconds packets cannot be opened and every one of
    // them logs a warning. It is expected and harmless, so only real errors are shown. Remove these two lines to see them again.
    "-Dorg.slf4j.simpleLogger.log.club.minnced.discord.jdave.DaveDecryptor=error",
    "-Dorg.slf4j.simpleLogger.log.net.dv8tion.jda.internal.audio.AudioPacket=error")

application {
    mainClass = "com.yuaworks.voicestandee.StandeeApp"
    applicationDefaultJvmArgs = jvmFlags
}

// The overlay alone with made-up people, to try the look without Discord:  .\gradlew demo
tasks.register<JavaExec>("demo") {
    group = "application"
    mainClass = "com.yuaworks.voicestandee.OverlayDemo"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs = jvmFlags
}

// The first prototype (only logs who speaks):  .\gradlew probe
tasks.register<JavaExec>("probe") {
    group = "application"
    mainClass = "com.yuaworks.voicestandee.SpeakingProbe"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs = jvmFlags
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}
