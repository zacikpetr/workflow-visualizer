import org.jetbrains.changelog.Changelog

plugins {
    id("java")
    // Kotlin pinned to the stdlib bundled in the floor IDE (2025.2 → 2.1.20) so
    // emitted metadata/bytecode matches the minimum target platform.
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    // IntelliJ Platform Gradle Plugin 2.x. The 1.x line (org.jetbrains.intellij)
    // ended at 1.17.4 and doesn't support Gradle 9.
    id("org.jetbrains.intellij.platform") version "2.16.0"
    // Single source of truth for release notes: CHANGELOG.md → plugin change-notes.
    id("org.jetbrains.changelog") version "2.5.0"
}

group = "tech.zacik"
version = "0.3.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Floor: IntelliJ IDEA Community 2025.2 (build 252).
        intellijIdeaCommunity("2025.2")
        // JSON became a dedicated bundled plugin in 2024.3+ (was a platform
        // module before). Same id we <depends> on in plugin.xml.
        bundledPlugin("com.intellij.modules.json")
    }

    // Native PlantUML render (Smetana engine → no graphviz/dot needed).
    implementation("net.sourceforge.plantuml:plantuml:1.2025.4")
    // Swing SVG viewer with built-in zoom/pan + <a> link activation events.
    // Exclude xml-apis/xerces: Batik drags its own copy of javax.xml.parsers.*,
    // which clashes with the JDK/platform XML across classloaders (PlantUML SVG
    // render → ClassCastException on DocumentBuilderFactory). Use the JDK's XML.
    // Exclude only the conflicting `xml-apis:xml-apis` (javax.xml.parsers.*) and
    // xercesImpl — but KEEP `xml-apis:xml-apis-ext`, which provides the SVG DOM
    // interfaces (org.w3c.dom.svg.*) Batik needs and which don't conflict.
    implementation("org.apache.xmlgraphics:batik-swing:1.19") {
        exclude(group = "xml-apis", module = "xml-apis")
        exclude(group = "xerces", module = "xercesImpl")
    }
    implementation("org.apache.xmlgraphics:batik-transcoder:1.19") {
        exclude(group = "xml-apis", module = "xml-apis")
        exclude(group = "xerces", module = "xercesImpl")
    }
    // JSON parsing for the .sw.json → PlantUML generator.
    implementation("com.google.code.gson:gson:2.14.0")
}

// Target JVM 21 (IDE 2025.2 runtime).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Faster dev runs.
    buildSearchableOptions = false
    // Verify binary API compatibility across the supported IDE range (since the
    // untilBuild is open-ended). `recommended()` derives the set from sinceBuild.
    pluginVerification {
        ides {
            recommended()
        }
    }
    pluginConfiguration {
        name = "Workflow Visualizer"
        ideaVersion {
            sinceBuild = "252"
            // No upper bound — built against 252 using stable APIs, so it
            // installs on newer IDEs (2025.3/2026.x/…) too.
            untilBuild = provider { null }
        }
        // changeNotes accepts HTML — keep them short here and link out to
        // CHANGELOG.md for the full per-release breakdown.
        // Derived from CHANGELOG.md (the section matching this version, or the
        // Unreleased section as a fallback) — no duplicated notes to keep in sync.
        changeNotes = provider {
            val releaseVersion = project.version.toString()
            with(changelog) {
                renderItem(
                    (getOrNull(releaseVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
}
