import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

plugins {
    kotlin("multiplatform") version "1.4-M3"
    application
    kotlin("plugin.serialization") version "1.4-M3"
    id("org.jmailen.kotlinter") version "2.4.1"
}

val kotlinVersion: String by project
val ktorVersion: String by project
val logbackVersion: String by project
val jooqVersion: String by project
val serializationVersion: String by project

group = "io.meltec.amadeus"
version = "0.0.1"

repositories {
    mavenLocal()
    jcenter()
    maven ("https://dl.bintray.com/kotlin/kotlin-eap")
    maven ("https://kotlin.bintray.com/kotlinx")
    maven("https://kotlin.bintray.com/ktor")
    maven("https://kotlin.bintray.com/kotlin-js-wrappers")
}

kotlin {
    jvm {
        withJava()
    }
    js {
        browser {
            binaries.executable()
            dceTask {
                keep("ktor-ktor-io.\$\$importsForInline\$\$.ktor-ktor-io.io.ktor.utils.io")
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("io.ktor:ktor-serialization:1.3.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common:0.20.0")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val jvmMain by getting {
            kotlin.srcDirs("build/generated-src/jooq/primary")
            dependencies {
                // JOOQ dependencies (both codegen & runtime)
                implementation("org.jooq:jooq:$jooqVersion")

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
        val jsMain by getting {
            dependencies {
                implementation(kotlin("stdlib-js"))

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js:$serializationVersion")
                //todo: bugfix in kx.serialization?
                implementation(npm("text-encoding", "latest"))
                implementation(npm("abort-controller", "latest"))

                implementation("io.ktor:ktor-client-js:$ktorVersion") //include http&websockets
                //todo: bugfix in ktor-client?
                implementation(npm("bufferutil", "latest")) //TODO: Uncomment this and stuff breaks. WHY?
                implementation(npm("utf-8-validate", "latest"))

                //ktor client js json
                implementation("io.ktor:ktor-client-json-js:$ktorVersion")
                implementation("io.ktor:ktor-client-serialization-js:$ktorVersion")
                implementation(npm("fs", "latest"))
            }
        }
    }
}

application {
    mainClassName = "io.meltec.amadeus.MainKt"
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

    val jvmJar by existing(Jar::class) {
        val taskName = if (project.hasProperty("isProduction")) {
            "jsBrowserProductionWebpack"
        } else {
            "jsBrowserDevelopmentWebpack"
        }
        val webpackTask = named<KotlinWebpack>(taskName)
        dependsOn(webpackTask)
        from(File(webpackTask.get().destinationDirectory, webpackTask.get().outputFileName))
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

    val run by existing(JavaExec::class) {
        dependsOn(jvmJar)
        classpath(jvmJar)
    }
}
