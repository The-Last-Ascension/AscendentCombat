plugins {
    id("java-library")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(fileTree("../../server/plugins") { include("AscendentCore-*.jar") })
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version.toString(), "description" to project.description.toString())
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    register<Copy>("deployToServer") {
        dependsOn(jar)
        val pluginVersion = project.version.toString()
        val pluginsDir = layout.projectDirectory.dir("../../server/plugins")
        doFirst {
            pluginsDir.asFile.listFiles { file ->
                file.name.startsWith("AscendentCombat-") && file.name.endsWith(".jar") && file.name != "AscendentCombat-$pluginVersion.jar"
            }?.forEach { it.delete() }
        }
        from(jar.flatMap { it.archiveFile })
        into(pluginsDir)
        rename { "AscendentCombat-$pluginVersion.jar" }
    }
}
