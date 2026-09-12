indra {
    javaVersions {
        target(11)
    }
}

dependencies {
    compileOnly(projects.core)
    compileOnly("com.google.code.gson", "gson", "2.8.8")
    compileOnly("com.google.inject", "guice", Versions.guiceVersion)
    compileOnly("com.velocitypowered", "velocity-api", "3.2.0-SNAPSHOT")
    compileOnly("org.slf4j", "slf4j-api", "1.7.36")
}

description = "One-time Bedrock binding-code gate for the Xintinglei Floodgate deployment"

tasks.jar {
    archiveBaseName.set("XintingleiBindGate")
    archiveVersion.set("")
}
