package net.corda.nodeapi.internal

import com.google.common.base.CaseFormat
import net.corda.core.schemas.MappedSchema

object MigrationHelpers {
    private const val MIGRATION_PREFIX = "migration"
    private const val CHANGELOG_NAME = "changelog-master"
    private val possibleMigrationExtensions = listOf(".xml", ".sql", ".yml", ".json")

    fun getMigrationResource(schema: MappedSchema, classLoader: ClassLoader): String? {
        val resource = migrationResourceNameForSchema(schema)
        // find the migration file in the classpath
        return possibleMigrationExtensions.map { "$resource$it" }.firstOrNull { classLoader.getResource(it) != null }
     }

    fun migrationResourceNameForSchema(schema: MappedSchema): String {
        val declaredMigration = schema.migrationResource
        return if (declaredMigration != null)
            "$MIGRATION_PREFIX/$declaredMigration"
        else
            defaultMigrationResourceNameForSchema(schema)
    }

    /**
     * Default naming convention - SchemaName will be transformed from camel case to lower_hyphen then add ".changelog-master".
     */
    fun defaultMigrationResourceNameForSchema(schema: MappedSchema): String {
        val name: String = schema::class.simpleName!!
        val fileName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, name)
        return "$MIGRATION_PREFIX/$fileName.$CHANGELOG_NAME"
    }
}
