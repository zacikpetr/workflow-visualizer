plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    // IntelliJ Platform Gradle Plugin (1.x). If you prefer 2.x, the DSL differs.
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "tech.zacik"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Native PlantUML render (Smetana engine → no graphviz/dot needed).
    implementation("net.sourceforge.plantuml:plantuml:1.2024.8")
    // Swing SVG viewer with built-in zoom/pan + <a> link activation events.
    // Exclude xml-apis/xerces: Batik drags its own copy of javax.xml.parsers.*,
    // which clashes with the JDK/platform XML across classloaders (PlantUML SVG
    // render → ClassCastException on DocumentBuilderFactory). Use the JDK's XML.
    // Exclude only the conflicting `xml-apis:xml-apis` (javax.xml.parsers.*) and
    // xercesImpl — but KEEP `xml-apis:xml-apis-ext`, which provides the SVG DOM
    // interfaces (org.w3c.dom.svg.*) Batik needs and which don't conflict.
    implementation("org.apache.xmlgraphics:batik-swing:1.17") {
        exclude(group = "xml-apis", module = "xml-apis")
        exclude(group = "xerces", module = "xercesImpl")
    }
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17") {
        exclude(group = "xml-apis", module = "xml-apis")
        exclude(group = "xerces", module = "xercesImpl")
    }
    // JSON parsing for the .sw.json → PlantUML generator.
    implementation("com.google.code.gson:gson:2.10.1")
}

// Target a stable IDE. Bump as needed; Batik/PlantUML are IDE-version agnostic.
intellij {
    // pluginName drives the built ZIP's base name and the expanded plugin
    // directory inside it — keep aligned with the display name in plugin.xml
    // and the URL in updatePlugins.xml.
    pluginName.set("workflow-visualizer")
    version.set("2023.3.6")
    type.set("IC")
    // JSON support is bundled in the IC platform — depend on it via
    // `<depends>com.intellij.modules.json</depends>` in plugin.xml; no explicit
    // gradle plugin id (the gradle plugin doesn't recognise the module id here).
    plugins.set(emptyList())
}

// Target JVM 17 (IDE 2023.3 runtime) without forcing a separate JDK-17 toolchain —
// compiles fine on a JDK 21 Gradle JVM.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        // No upper bound — built against an old platform (233) using stable APIs,
        // so it installs on newer IDEs (2024.x/2025.x/…) too.
        untilBuild.set(provider { null })
        // changeNotes accepts HTML — keep them short here and link out to
        // CHANGELOG.md for the full per-release breakdown. Update for every
        // release; the IDE shows this snippet in the plugin-update dialog.
        changeNotes.set(
            """
            <b>0.1.0</b> — Initial release.
            <ul>
              <li>Live PlantUML diagram with bidirectional navigation.</li>
              <li>Eight inspections + quick-fixes (missing / duplicate / unreachable / unused / terminal / switch / event-timeout).</li>
              <li>PSI references: Go to Declaration, Find Usages, Rename.</li>
              <li>Gutter usage popup, hover preview, semantic coloring.</li>
              <li>Mutation badges, dead-code dimming, SVG / PUML export.</li>
            </ul>
            """.trimIndent(),
        )
    }
    // Faster dev runs.
    buildSearchableOptions { enabled = false }
}
