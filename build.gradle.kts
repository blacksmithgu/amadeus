plugins {
    kotlin("multiplatform") version "1.3.72"
    application
    kotlin("plugin.serialization") version "1.3.72"
    id("org.jmailen.kotlinter") version "2.4.1"
}

val kotlinVersion: String by project
val ktorVersion: String by project
val logbackVersion: String by project
val jooqVersion: String by project

group = "io.meltec.amadeus"
version = "0.0.1"

repositories {
    mavenLocal()
    jcenter()
    maven("https://kotlin.bintray.com/ktor")
    maven("https://kotlin.bintray.com/kotlin-js-wrappers")
}

kotlin {
    jvm {
        withJava()
    }
    sourceSets {
        val jvmMain by getting {
            kotlin.srcDirs("build/generated-src/jooq/primary")
            dependencies {
                // JOOQ dependencies (both codegen & runtime)
                implementation("org.jooq:jooq")

                // sqlite JDBC
                implementation("org.xerial:sqlite-jdbc:3.32.3")

                // ktor dependencies for all of the various ktor features.
                implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
                implementation("io.ktor:ktor-server-netty:$ktorVersion")
                implementation("ch.qos.logback:logback-classic:$logbackVersion")
                implementation("io.ktor:ktor-server-core:$ktorVersion")
                implementation("io.ktor:ktor-html-builder:$ktorVersion")
                implementation("org.jetbrains:kotlin-css-jvm:1.0.0-pre.31-kotlin-1.2.41")
                implementation("io.ktor:ktor-server-host-common:$ktorVersion")
                implementation("io.ktor:ktor-metrics:$ktorVersion")
                implementation("io.ktor:ktor-server-sessions:$ktorVersion")
                implementation("io.ktor:ktor-websockets:$ktorVersion")
                implementation("io.ktor:ktor-auth:$ktorVersion")
                implementation("io.ktor:ktor-auth-jwt:$ktorVersion")
                implementation("io.ktor:ktor-serialization:$ktorVersion")
                implementation("io.ktor:ktor-locations:$ktorVersion")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("io.ktor:ktor-server-tests:$ktorVersion")
            }
        }
    }
}

application {
    mainClassName = "io.ktor.server.netty.EngineMain"
}

apply(from = "jooq.gradle")

kotlinter {
    ignoreFailures = false
    indentSize = 4
    disabledRules = arrayOf("no-wildcard-imports")
}

tasks {
    val generatePrimaryJooqSchemaSource by existing

    // TODO: Please figure out how to remove all the "experimental" warnings.
    val compileKotlinJvm by existing(org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile::class) {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xinline-classes", "-Xopt-in=kotlin.RequiresOptIn")
        }
        // Need to explicitly add the jooq generated schema files to the kotlin path.
        dependsOn(generatePrimaryJooqSchemaSource)
    }

    // Shorthand task for running all jooq-related tasks.
    val jooq by registering {
        dependsOn(generatePrimaryJooqSchemaSource)
    }

    /** Cleanup automation (for deleting runtime files). */
    val cleanRuntimeFiles by registering(Delete::class) {
        delete("work")
        delete("songs")
        delete("amadeus.db")
        delete("amadeus-test.db")
    }

    clean {
        dependsOn(cleanRuntimeFiles)
    }
}
