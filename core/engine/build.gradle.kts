plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/** Prints the corpus accuracy report (IMPLEMENTATION_PLAN.md §6.12). */
tasks.register<Test>("evaluate") {
    description = "Runs the golden SMS corpus and prints accuracy per field and type."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*CorpusEvaluationTest") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

/** Anonymises exported messages before they enter the corpus: `-Pin=raw.tsv -Pout=new.jsonl`. */
tasks.register<JavaExec>("anonymize") {
    description = "Anonymises raw sender<TAB>body lines into corpus JSONL skeletons."
    group = "corpus"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.impeesa.finance.core.engine.tools.AnonymizerKt")
    args(
        (project.findProperty("in") ?: "raw.tsv").toString(),
        (project.findProperty("out") ?: "anonymized.jsonl").toString(),
    )
}
