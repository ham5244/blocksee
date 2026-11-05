plugins {
    java
}

group = "dev.blocksee"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Change this to match your exact Paper version if it doesn't resolve
    compileOnly("io.papermc.paper:paper-api:26.3.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
