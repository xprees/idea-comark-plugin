package cz.xprees.comark.format.studio

/**
 * The Nuxt Content collection a document belongs to, as far as Nuxt Studio 1.8.0's database path needs it: the collection
 * type, the user fields of its `content.config.ts` schema and the file the document is stored in.
 *
 * Nuxt Content 3.16.2 stores every document as a row of the collection's table. Page collections have the standard page
 * columns (`title`, `description`, `path`, `seo`, `body`, `navigation`) and extract a title and description from the body
 * (`contentHeading`); data collections only have `id`, `stem`, `extension` and `meta`. Every front matter key that is not a
 * column ends up in the `meta` JSON column. See [StudioDbPath].
 *
 * @property fields the user fields of the collection schema, in declaration order. Nuxt Content orders the columns itself
 *   (`id`, `title`, then every other column sorted by code unit), so the declaration order does not matter for the output;
 *   it is kept for display.
 * @property fileName the content file name or path (`content/docs/1.getting-started.md`); only its last segment is used, for
 *   the title Nuxt Content derives when a page has none.
 * @property schemaKnown whether [fields] is the collection's real schema. `false` when the schema could not be read (for
 *   example no `content.config.ts`); the emulation then uses Studio's defaults, which is exact for collections without user
 *   fields.
 */
data class StudioCollection(
    val type: Type,
    val fields: List<Field> = emptyList(),
    val fileName: String = DEFAULT_FILE_NAME,
    val schemaKnown: Boolean = true,
) {
    enum class Type { PAGE, DATA }

    /**
     * The SQL column type Nuxt Content gives a schema field (`runtime/internal/schema.js` `getPropertyType`), which decides
     * how its value is converted when the row is written and read back (`generateCollectionInsert`, `refineContentFields`).
     */
    enum class ColumnType {
        /** `z.string()`, `z.enum()`: VARCHAR, `String(value)`. */
        STRING,

        /** `z.number()`: INT, `Number(value)`; a value that is not a finite number cannot be inserted. */
        NUMBER,

        /** `z.boolean()`: BOOLEAN, `!!value`. */
        BOOLEAN,

        /** `z.date()`: DATE, `YYYY-MM-DD` (UTC); an invalid date cannot be inserted. */
        DATE,

        /** A `date-time` string: DATETIME, `YYYY-MM-DD HH:MM:SS` (UTC). */
        DATETIME,

        /** `z.array()`, `z.object()`, unions and everything else: a JSON TEXT column (`JSON.stringify` / `JSON.parse`). */
        JSON,
    }

    /**
     * A schema field. [default] is the zod `.default(...)` value as a JavaScript value ([String], [Double], [Boolean], `null`,
     * [List], [Map]); it is only used when [hasDefault].
     */
    data class Field(val name: String, val type: ColumnType = ColumnType.STRING, val hasDefault: Boolean = false, val default: Any? = null) {
        companion object {
            fun withDefault(name: String, type: ColumnType, default: Any?) = Field(name, type, true, default)
        }
    }

    /** A column of the collection's table: the standard columns and the user [fields]. */
    internal class Column(val name: String, val type: ColumnType, val hasDefault: Boolean, val default: Any?)

    /** All columns, keyed by name: the standard columns of the collection type, then user fields not already defined. */
    internal val columns: Map<String, Column> by lazy {
        val m = LinkedHashMap<String, Column>()
        fun std(name: String, type: ColumnType, hasDefault: Boolean = false, default: Any? = null) {
            m[name] = Column(name, type, hasDefault, default)
        }
        std("id", ColumnType.STRING)
        std("stem", ColumnType.STRING)
        std("extension", ColumnType.STRING)
        std("meta", ColumnType.JSON)
        if (type == Type.PAGE) {
            std("path", ColumnType.STRING)
            std("title", ColumnType.STRING)
            std("description", ColumnType.STRING)
            std("seo", ColumnType.JSON, true, LinkedHashMap<String, Any?>())
            std("body", ColumnType.JSON)
            std("navigation", ColumnType.JSON, true, true)
        }
        for (f in fields) if (f.name !in m) m[f.name] = Column(f.name, f.type, f.hasDefault, f.default)
        m
    }

    /** `getOrderedSchemaKeys`: `id`, `title` (when a column), then every other column sorted by UTF-16 code unit. */
    internal val orderedKeys: List<String> by lazy {
        val keys = LinkedHashSet<String>()
        keys += "id"
        if ("title" in columns) keys += "title"
        keys += columns.keys.sorted()
        keys.toList()
    }

    /** The last segment of [fileName] without its extension (`describeId`'s `basename`). */
    internal val baseName: String
        get() {
            val last = fileName.split('/', ':').last()
            return Regex("^(.*)\\.([^.]+)\\z").find(last)?.groupValues?.get(1) ?: ""
        }

    companion object {
        const val DEFAULT_FILE_NAME = "content/index.md"

        /** Studio's defaults: a page collection with Nuxt Content's standard schema and no user fields. */
        val DEFAULTS = StudioCollection(Type.PAGE)

        /** A page collection with the standard schema plus [fields]. */
        fun page(fileName: String = DEFAULT_FILE_NAME, fields: List<Field> = emptyList()) = StudioCollection(Type.PAGE, fields, fileName)

        /** A data collection with [fields]. */
        fun data(fileName: String = DEFAULT_FILE_NAME, fields: List<Field> = emptyList()) = StudioCollection(Type.DATA, fields, fileName)

        /** Studio's defaults for a document stored in [fileName] whose collection schema is not known. */
        fun defaults(fileName: String = DEFAULT_FILE_NAME) = StudioCollection(Type.PAGE, emptyList(), fileName, schemaKnown = false)
    }
}
