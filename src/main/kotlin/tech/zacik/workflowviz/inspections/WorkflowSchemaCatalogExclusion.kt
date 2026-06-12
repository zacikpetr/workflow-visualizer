package tech.zacik.workflowviz.inspections

import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.remote.JsonSchemaCatalogExclusion

/**
 * Blocks schemastore.org **catalog auto-binding** for `.sw.json` files.
 *
 * Without this, IntelliJ matches `*.sw.json` against the Serverless Workflow
 * **v1.0** schema from schemastore.org (where the top-level keys became `do`
 * and `document`), and floods Problems with `Missing required properties
 * 'do', 'document'` on every file. Projects on v0.8 (`specVersion: "0.8"`,
 * still common) get nothing but noise.
 *
 * Why this EP and not [com.jetbrains.jsonSchema.extension.JsonSchemaEnabler]:
 * the schema service **ORs** all enablers, and the built-in JSON-files enabler
 * returns true for every JSON file — a disabling enabler is a no-op. The
 * catalog exclusion targets exactly the schemastore lookup, so user-assigned
 * schema mappings on `.sw.json` keep working.
 */
class WorkflowSchemaCatalogExclusion : JsonSchemaCatalogExclusion {
    override fun isExcluded(file: VirtualFile): Boolean = file.name.endsWith(".sw.json")
}
