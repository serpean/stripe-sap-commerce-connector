// Runs the @UnitTest classes of the Stripe extensions with plain JUnit 4.
// It does not start the SAP Commerce platform. The classpath comes from a
// local platform. Nothing from the platform is copied into this repo.
plugins {
    java
}

val platformDir: File = file(
    providers.gradleProperty("platformDir").orElse(
        providers.environmentVariable("HYBRIS_PLATFORM").orElse("/Volumes/e/y/stripe-ref-platform")
    ).get()
)
val hybrisBin = File(platformDir, "hybris/bin")
val extRoot = file("../../core-customize/hybris/custom/stripe")
val stripeExts = listOf("stripecore", "stripefacades", "stripeocc")

fun existing(vararg paths: String) = paths.map { file(it) }.filter { it.isDirectory }

val modelsJar = File(hybrisBin, "platform/bootstrap/bin/models.jar")
if (!modelsJar.isFile) {
    throw GradleException("models.jar not found in $modelsJar: run ant all once in the platform")
}

// The "classes" folders of the SAP extensions, "depth" levels below "root".
fun classesDirs(root: File, depth: Int): List<File> {
    val parents = (1 until depth).fold(listOf(root)) { dirs, _ ->
        dirs.flatMap { it.listFiles { f -> f.isDirectory }?.toList() ?: emptyList() }
    }
    return parents.flatMap { it.listFiles { f -> f.isDirectory }?.toList() ?: emptyList() }
        .flatMap { listOf(File(it, "classes"), File(it, "web/webroot/WEB-INF/classes")) }
        .filter { it.isDirectory }
}

// SAP jars and compiled classes of the SAP extensions (the custom folder is not included).
val sapClasspath = files(
    modelsJar,
    fileTree(File(hybrisBin, "platform/bootstrap/bin")) { include("*.jar") },
    fileTree(File(hybrisBin, "platform/lib")) { include("**/*.jar") },
    fileTree(File(hybrisBin, "platform/ext")) {
        include("*/lib/**/*.jar", "*/bin/*.jar", "*/web/webroot/WEB-INF/lib/*.jar")
    },
    fileTree(File(hybrisBin, "modules")) {
        include("*/*/lib/**/*.jar", "*/*/bin/*.jar", "*/*/web/webroot/WEB-INF/lib/*.jar")
    },
    classesDirs(File(hybrisBin, "platform/ext"), 1),
    classesDirs(File(hybrisBin, "modules"), 2)
)
// core-items.xml here lets the SAP bootstrap find the platform home.
val coreResources = files(File(hybrisBin, "platform/ext/core/resources"))
val extResources = files(stripeExts.flatMap { existing("$extRoot/$it/resources") })
val extLibs = fileTree(File(extRoot, "stripecore/lib")) { include("*.jar") }

sourceSets {
    main {
        java.setSrcDirs(stripeExts.flatMap {
            existing("$extRoot/$it/src", "$extRoot/$it/web/src", "$extRoot/$it/gensrc")
        })
        compileClasspath = sapClasspath + extLibs
        runtimeClasspath = output + sapClasspath + extLibs + coreResources + extResources
    }
    test {
        java.setSrcDirs(stripeExts.flatMap { existing("$extRoot/$it/testsrc") })
        compileClasspath = main.get().output + sapClasspath + extLibs
        runtimeClasspath = output + main.get().output + sapClasspath + extLibs + coreResources + extResources
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
    options.isWarnings = false
}

// Keep the @UnitTest classes only: the @IntegrationTest classes need the platform.
val unitTestClasses: List<String> = stripeExts.flatMap { ext ->
    existing("$extRoot/$ext/testsrc").flatMap { dir ->
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") && it.readText().contains("@UnitTest") }
            .map { it.relativeTo(dir).path.removeSuffix(".java").replace(File.separatorChar, '.') }
            .toList()
    }
}

tasks.test {
    useJUnit()
    outputs.upToDateWhen { false } // always run, so the count is always printed
    // The SAP models read the platform home when they load.
    val platformHome = File(hybrisBin, "platform").absolutePath
    systemProperty("platformhome", platformHome)
    environment("PLATFORM_HOME", platformHome)
    include(unitTestClasses.map { it.replace('.', '/') + ".class" })
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    doFirst { logger.lifecycle("Running ${unitTestClasses.size} @UnitTest classes") }
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ d, r ->
        if (d.parent == null) {
            println("Tests: ${r.testCount}, passed: ${r.successfulTestCount}, failed: ${r.failedTestCount}, skipped: ${r.skippedTestCount}")
        }
    }))
}
