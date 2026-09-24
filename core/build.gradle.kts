plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The core tests are plain Java with no test framework, so they also run with just a JDK.
tasks.register<JavaExec>("coreTest") {
    group = "verification"
    description = "Runs the Dilmun core tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.dilmun.core.CoreTest")
}

tasks.named("check") { dependsOn("coreTest") }
