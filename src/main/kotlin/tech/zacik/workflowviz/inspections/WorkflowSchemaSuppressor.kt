package tech.zacik.workflowviz.inspections

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaEnabler

/**
 * Disables JetBrains' automatic JSON Schema validation on `.sw.json` files.
 *
 * Without this, IntelliJ matches `*.sw.json` against the Serverless Workflow
 * **v1.0** schema from schemastore.org (where the top-level keys became `do`
 * and `document`), and floods Problems with `Missing required properties
 * 'do', 'document'` on every file. Projects on v0.8 (`specVersion: "0.8"`,
 * still common) get nothing but noise.
 *
 * Trade-off: this plugin's users lose schema-driven completion and validation
 * on `.sw.json`. The bundled inspections plus reference contributor cover the
 * core slice (states / functions / events / errors), which is enough for most
 * editing flows.
 *
 * Any `JsonSchemaEnabler` returning `false` vetoes schema for a file — the
 * service ANDs all enablers, so registering a single disabling enabler is
 * enough regardless of what others say.
 */
class WorkflowSchemaSuppressor : JsonSchemaEnabler {
    override fun isEnabledForFile(file: VirtualFile, project: Project?): Boolean =
        !file.name.endsWith(".sw.json")

    override fun shouldShowSwitcherWidget(file: VirtualFile?): Boolean =
        file == null || !file.name.endsWith(".sw.json")
}
