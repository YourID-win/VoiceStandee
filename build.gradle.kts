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
    implementation("net.dv8tion:JDA:6.4.1") {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
    // Discord's end-to-end encryption for voice (DAVE): the part that lets the bot join a voice channel at all
    implementation("club.minnced:jdave-api:0.1.8")
    runtimeOnly("club.minnced:jdave-native-linux-x86-64:0.1.8")
    runtimeOnly("club.minnced:jdave-native-win-x86-64:0.1.8")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass = "com.yuaworks.voicestandee.SpeakingProbe"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}
