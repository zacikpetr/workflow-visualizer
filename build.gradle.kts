import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
version = "0.5.0"

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
        // BasePlatformTestCase & friends for the inspection / reference tests.
        testFramework(TestFrameworkType.Platform)
    }

    // Native PlantUML render (Smetana engine → no graphviz/dot needed).
    // Smetana is compiled into the plantuml jar itself; ELK is PlantUML's
    // *optional alternative* layout engine, EMF + Guava exist only to serve
    // ELK, and JLaTeXMath only backs <latex> markup our generated diagrams
    // never emit. Excluding them trims ~8 MB (~23 %) off the plugin zip.
    implementation("net.sourceforge.plantuml:plantuml:1.2025.4") {
        exclude(group = "org.eclipse.elk")
        exclude(group = "org.eclipse.emf")
        exclude(group = "com.google.guava")
        exclude(group = "org.scilab.forge")
    }
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

    // The platform test framework is JUnit3/4-based.
    testImplementation("junit:junit:4.13.2")
}

// Target JVM 21 (IDE 2025.2 runtime).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    jvmToolchain(21)
}

// Keep in sync with pluginConfiguration.ideaVersion below — also consumed by
// the updatePlugins.xml generator.
val pluginSinceBuild = "252"

intellijPlatform {
    // Index the settings UI so users can find "Workflow Visualizer" via the
    // Settings search box. CI/release builds only (-PciBuild): the headless
    // IDE launch is slow AND it writes its own defaults into the sandbox
    // config shared with runIde — locally that flipped the runIde editor
    // scheme to the light "Default" (2026-06-12).
    buildSearchableOptions = hasProperty("ciBuild")
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
            sinceBuild = pluginSinceBuild
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

/**
 * Regenerates updatePlugins.xml (the custom-repository manifest) from the
 * project version, `pluginSinceBuild` and the matching CHANGELOG.md section —
 * replacing the previous triple hand-sync of version, URL and change-notes.
 * The release workflow runs it after the GitHub Release exists, so the
 * advertised download URL never 404s.
 */
val generateUpdatePluginsXml by tasks.registering {
    group = "publishing"
    description = "Regenerate updatePlugins.xml from version + CHANGELOG.md"
    doLast {
        val v = project.version.toString()
        val notes = with(changelog) {
            renderItem(
                (getOrNull(v) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }.trim().prependIndent("            ")
        file("updatePlugins.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!--
            |  Custom-repository manifest consumed by IntelliJ when the user adds this
            |  repo URL under Settings → Plugins → ⚙ → Manage Plugin Repositories.
            |
            |  GENERATED by `./gradlew generateUpdatePluginsXml` from the project
            |  version and CHANGELOG.md — do not edit by hand (see RELEASING.md).
            |-->
            |<plugins>
            |    <plugin
            |            id="tech.zacik.workflowviz"
            |            url="https://github.com/zacikpetr/workflow-visualizer/releases/download/v$v/workflow-visualizer-$v.zip"
            |            version="$v">
            |        <idea-version since-build="$pluginSinceBuild"/>
            |        <name>Workflow Visualizer</name>
            |        <vendor email="zacik.petr@gmail.com" url="https://github.com/zacikpetr/workflow-visualizer">Petr Žáčík</vendor>
            |        <description><![CDATA[
            |            IDE-native preview and analysis for Serverless Workflow (.sw.json): live
            |            PlantUML diagram, navigation, inspections, and semantic highlighting.
            |        ]]></description>
            |        <change-notes><![CDATA[
            |            <b>$v</b>
            |$notes
            |        ]]></change-notes>
            |    </plugin>
            |</plugins>
            |""".trimMargin(),
        )
    }
}
