buildscript {
    repositories {
        maven { url = 'https://maven.aliyun.com/repository/public' }
        maven { url = 'https://maven.minecraftforge.net' }
        mavenCentral()
    }
    dependencies {
        classpath 'net.minecraftforge.gradle:ForgeGradle:6.0.18'
    }
}
apply plugin: 'net.minecraftforge.gradle'

version = '1.0.0'
group = 'com.example.telly'
archivesBaseName = 'telly'

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: 'official', version: '1.20.1'
    runs {
        client {
            workingDirectory project.file('run')
            property 'forge.logging.mojang.level', 'debug'
            mods {
                telly {
                    source sourceSets.main
                }
            }
        }
    }
}

repositories {
    maven { url = 'https://maven.aliyun.com/repository/public' }
    maven { url = 'https://maven.minecraftforge.net' }
    mavenCentral()
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
}

jar {
    manifest {
        attributes([
            "Specification-Title": "telly",
            "Specification-Vendor": "example",
            "Specification-Version": "1",
            "Implementation-Title": project.name,
            "Implementation-Version": project.version,
            "Implementation-Vendor": "example",
            "Implementation-Timestamp": new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")
        ])
    }
}