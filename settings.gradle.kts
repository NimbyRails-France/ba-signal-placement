pluginManagement {
    val sdk = providers.gradleProperty("nrfSdkDir").orElse(providers.environmentVariable("NRF_KOTLIN_SDK"))
        .orNull ?: error("Configurer nrfSdkDir vers le kit SDK Kotlin de développement.")
    repositories { maven { url = uri(file(sdk).resolve("gradle-repository")) }; gradlePluginPortal(); mavenCentral() }
    // The API, bridge and Gradle plugin come from the same selected kit.
    val metadata = groovy.json.JsonSlurper().parseText(file(sdk).resolve("sdk.json").readText().removePrefix("\uFEFF")) as Map<*, *>
    plugins { id("fr.nimbyrails.mod") version (metadata["gradlePluginVersion"] as String) }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "signal-placement-mod"
