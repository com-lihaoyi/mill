plugins {
    java
}
dependencies {
    constraints {
        implementation("org.apache.commons:commons-text:1.13.0")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
tasks.named<Test>("test") {
    useJUnitPlatform()
}
