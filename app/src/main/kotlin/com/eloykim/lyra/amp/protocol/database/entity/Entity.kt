package com.eloykim.lyra.amp.protocol.database.entity

/**
 * JPA-style entity metadata for the SQLite kernel (port of
 * `src/database/entity/entity.ts` and `src/database/entity/meta.ts`).
 *
 * The reference compiles with `erasableSyntaxOnly`, so Java annotations are
 * plain immutable metadata objects. The names and semantics intentionally mirror
 * JPA: an entity owns a table, scalar fields map to columns, and associations
 * carry their own fetch, cascade, join-column and orphan-removal rules.
 *
 * This is the "rich" metadata. [TableMeta] is the compact view the SQL and
 * repository layers consume; `toTableMeta` derives it from a validated entity.
 */

/**
 * Primitive identifier type accepted by an entity (`number | bigint | string`
 * in the reference).
 */
typealias PrimaryKey = Any

/** Logical entity name used by associations (the JPA `@Entity(name)` value). */
typealias EntityName = String

/**
 * Table metadata shared by entities, repositories and the SQL layer (port of
 * `src/database/entity/meta.ts`).
 */
data class TableMeta(
    val table: String,
    val primaryKey: String,
    val columns: List<String>,
)

/** Target of an association: the target's logical entity name. */
data class EntityTarget(val name: String)

/** Whether an association or basic attribute is loaded eagerly or lazily. */
enum class Fetch(val wire: String) {
    EAGER("eager"),
    LAZY("lazy"),
}

/** JPA cascade modes. `ALL` is shorthand for every other mode. */
enum class CascadeType(val wire: String) {
    ALL("All"),
    PERSIST("Persist"),
    MERGE("Merge"),
    REMOVE("Remove"),
    REFRESH("Refresh"),
    DETACH("Detach"),
}

/** JPA identifier-generation strategies. */
enum class GenerationType(val wire: String) {
    AUTO("Auto"),
    IDENTITY("Identity"),
    SEQUENCE("Sequence"),
    TABLE("Table"),
}

/** Storage strategy for an enum-valued field. */
enum class Enumeration(val wire: String) {
    ORDINAL("Ordinal"),
    STRING("String"),
}

/** Temporal precision for a date/time field. */
enum class Temporal(val wire: String) {
    DATE("Date"),
    TIME("Time"),
    TIMESTAMP("Timestamp"),
}

/** Whether persistent fields are discovered through fields or accessors. */
enum class Access(val wire: String) {
    FIELD("Field"),
    PROPERTY("Property"),
}

/** Entity lifecycle state, mirroring the JPA `EntityManager` states. */
enum class EntityState(val wire: String) {
    NEW("New"),
    MANAGED("Managed"),
    DETACHED("Detached"),
    REMOVED("Removed"),
}

/** JPA inheritance strategies. */
enum class InheritanceStrategy(val wire: String) {
    SINGLE_TABLE("SingleTable"),
    JOINED("Joined"),
    TABLE_PER_CLASS("TablePerClass"),
}

/** JPA lifecycle callback events. */
enum class LifecycleEvent(val wire: String) {
    PRE_PERSIST("prePersist"),
    POST_PERSIST("postPersist"),
    PRE_UPDATE("preUpdate"),
    POST_UPDATE("postUpdate"),
    PRE_REMOVE("preRemove"),
    POST_REMOVE("postRemove"),
    POST_LOAD("postLoad"),
}

/** Callback invoked for one entity lifecycle event. */
typealias LifecycleCallback = (Any?) -> Unit

/** Lifecycle callbacks attached to an entity, keyed by event. */
typealias LifecycleCallbacks = Map<LifecycleEvent, List<LifecycleCallback>>

/** JPA `AttributeConverter` analogue. */
interface AttributeConverter<X, Y> {

    fun convertToDatabaseColumn(attribute: X): Y

    fun convertToEntityAttribute(dbData: Y): X
}

/**
 * Column mapping options, corresponding to JPA `@Column`.
 *
 * Omitted values follow JPA defaults; the kernel never silently rewrites a
 * mapping, so an absent option stays absent.
 */
data class Column<T>(
    val name: String? = null,
    val table: String? = null,
    val nullable: Boolean? = null,
    val unique: Boolean? = null,
    val insertable: Boolean? = null,
    val updatable: Boolean? = null,
    val length: Int? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    val columnDefinition: String? = null,
    val converter: AttributeConverter<T, Any?>? = null,
)

/** Identifier-generation options, corresponding to JPA `@GeneratedValue`. */
data class GeneratedValue(
    val strategy: GenerationType? = null,
    val generator: String? = null,
    val initialValue: Int? = null,
    val allocationSize: Int? = null,
)

/** Metadata for one persistent scalar field. */
data class Field(
    val name: String,
    val column: Column<*>? = null,
    val id: Boolean? = null,
    val generated: GeneratedValue? = null,
    val embeddedId: Boolean? = null,
    val version: Boolean? = null,
    val transient: Boolean? = null,
    val optional: Boolean? = null,
    val fetch: Fetch? = null,
    val enumeration: Enumeration? = null,
    val temporal: Temporal? = null,
    val lob: Boolean? = null,
    val access: Access? = null,
    val get: ((Any?) -> Any?)? = null,
    val set: ((Any?, Any?) -> Unit)? = null,
)

/** Foreign-key options for a join column, corresponding to JPA `@ForeignKey`. */
data class ForeignKey(
    val name: String? = null,
    val definition: String? = null,
)

/** Join-column mapping, corresponding to JPA `@JoinColumn`. */
data class JoinColumn(
    val name: String? = null,
    val referencedColumnName: String? = null,
    val nullable: Boolean? = null,
    val unique: Boolean? = null,
    val insertable: Boolean? = null,
    val updatable: Boolean? = null,
    val columnDefinition: String? = null,
    val table: String? = null,
    val foreignKey: ForeignKey? = null,
)

/** Unique constraint metadata for a table or join table. */
data class UniqueConstraint(
    val name: String? = null,
    val columnNames: List<String>,
)

/** Index metadata for a table or join table. */
data class Index(
    val name: String? = null,
    val columnList: String,
    val unique: Boolean? = null,
)

/** Join-table mapping, corresponding to JPA `@JoinTable`. */
data class JoinTable(
    val name: String,
    val catalog: String? = null,
    val schema: String? = null,
    val joinColumns: List<JoinColumn>? = null,
    val inverseJoinColumns: List<JoinColumn>? = null,
    val uniqueConstraints: List<UniqueConstraint>? = null,
    val indexes: List<Index>? = null,
)

/** Physical table mapping, corresponding to JPA `@Table`. */
data class Table(
    val name: String,
    val catalog: String? = null,
    val schema: String? = null,
    val uniqueConstraints: List<UniqueConstraint>? = null,
    val indexes: List<Index>? = null,
)

/** Cascade options for an association. */
data class Cascade(val types: List<CascadeType>)

/** Inheritance mapping metadata, corresponding to JPA `@Inheritance`. */
data class Inheritance(val strategy: InheritanceStrategy)

/** Discriminator-column options for single-table inheritance. */
data class DiscriminatorColumn(
    val column: Column<String> = Column(),
    val discriminatorType: String? = null,
)

/**
 * Common association metadata, corresponding to JPA's association annotations.
 *
 * The reference uses `T` only to constrain `mappedBy` at compile time. Kotlin
 * keeps `mappedBy` as a plain target property name; the runtime metadata is the
 * same.
 */
sealed interface Relation {

    val name: String

    val target: EntityTarget

    val fetch: Fetch?

    val cascade: Cascade?

    val optional: Boolean?

    /** Join table, for the association kinds that own one. */
    val joinTable: JoinTable?
}

/** One-to-one association metadata, corresponding to JPA `@OneToOne`. */
data class OneToOne(
    override val name: String,
    override val target: EntityTarget,
    override val fetch: Fetch? = null,
    override val cascade: Cascade? = null,
    override val optional: Boolean? = null,
    val mappedBy: String? = null,
    val joinColumns: List<JoinColumn>? = null,
    override val joinTable: JoinTable? = null,
    val orphanRemoval: Boolean? = null,
) : Relation

/** One-to-many association metadata, corresponding to JPA `@OneToMany`. */
data class OneToMany(
    override val name: String,
    override val target: EntityTarget,
    override val fetch: Fetch? = null,
    override val cascade: Cascade? = null,
    override val optional: Boolean? = null,
    val mappedBy: String? = null,
    override val joinTable: JoinTable? = null,
    val orderBy: String? = null,
    val orphanRemoval: Boolean? = null,
) : Relation

/** Many-to-one association metadata, corresponding to JPA `@ManyToOne`. */
data class ManyToOne(
    override val name: String,
    override val target: EntityTarget,
    override val fetch: Fetch? = null,
    override val cascade: Cascade? = null,
    override val optional: Boolean? = null,
    val joinColumns: List<JoinColumn>? = null,
) : Relation {
    override val joinTable: JoinTable? get() = null
}

/** Many-to-many association metadata, corresponding to JPA `@ManyToMany`. */
data class ManyToMany(
    override val name: String,
    override val target: EntityTarget,
    override val fetch: Fetch? = null,
    override val cascade: Cascade? = null,
    override val optional: Boolean? = null,
    val mappedBy: String? = null,
    override val joinTable: JoinTable? = null,
    val orderBy: String? = null,
) : Relation

/**
 * Complete metadata for one JPA-style entity.
 *
 * Data-only: it can be copied, compared, or passed across module boundaries
 * without a class-decorator runtime.
 */
data class Entity(
    val name: String,
    val table: Table?,
    val access: Access? = null,
    val fields: List<Field> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val idClass: String? = null,
    val inheritance: Inheritance? = null,
    val discriminatorColumn: DiscriminatorColumn? = null,
    val discriminatorValue: String? = null,
    val listeners: List<String> = emptyList(),
    val lifecycle: LifecycleCallbacks = emptyMap(),
    val cacheable: Boolean? = null,
)

/** Thrown when entity metadata violates a JPA structural rule. */
class EntityMetadataError(
    entityName: String,
    problems: List<String>,
) : IllegalArgumentException("Invalid entity metadata for $entityName: ${problems.joinToString("; ")}") {

    /** All validation problems discovered for the entity. */
    val problems: List<String> = ArrayList(problems)
}

/**
 * Validate the structural rules that can be checked without a database.
 *
 * Cross-entity rules such as `mappedBy` resolution require the registry and are
 * left to the persistence layer, exactly as in the reference.
 *
 * @return a list of problems; empty means the metadata is valid.
 */
fun validateEntity(entity: Entity): List<String> {
    val problems = ArrayList<String>()
    val entityName = entity.name.trim()
    if (entityName.isEmpty()) {
        problems += "entity name must not be empty"
    }

    val table = entity.table
    if (table == null) {
        problems += "entity must declare a table"
    } else {
        if (table.name.trim().isEmpty()) {
            problems += "table name must not be empty"
        }
        problems += validateTable(table)
    }

    val names = LinkedHashSet<String>()
    var identifiers = 0
    var embeddedIdentifiers = 0
    var generatedIdentifiers = 0
    var versions = 0

    for (field in entity.fields) {
        if (field.name.trim().isEmpty()) {
            problems += "field name must not be empty"
            continue
        }
        if (!names.add(field.name)) {
            problems += "duplicate property name: ${field.name}"
        }

        if (field.id == true) {
            identifiers += 1
        }
        if (field.embeddedId == true) {
            identifiers += 1
            embeddedIdentifiers += 1
        }
        if (field.generated != null && field.id == true) {
            generatedIdentifiers += 1
        }
        if (field.version == true) {
            versions += 1
        }
        if (field.id == true && field.version == true) {
            problems += "${field.name} cannot be both an id and a version"
        }
        if (field.id == true && field.embeddedId == true) {
            problems += "${field.name} cannot be both id and embeddedId"
        }
        if (field.embeddedId == true && field.version == true) {
            problems += "${field.name} cannot be both an embeddedId and a version"
        }
        if (field.embeddedId == true && field.transient == true) {
            problems += "${field.name} cannot be both an embeddedId and transient"
        }
        if (field.id == true && field.transient == true) {
            problems += "${field.name} cannot be both an id and transient"
        }
        if (field.generated != null && field.id != true && field.embeddedId != true) {
            problems += "${field.name} declares generated value without id"
        }
        if (field.transient == true && field.column != null) {
            problems += "${field.name} is transient but declares a column"
        }
        field.column?.let { column -> problems += validateColumn(field.name, column) }
        if (field.temporal != null && field.lob == true) {
            problems += "${field.name} cannot be both temporal and a LOB"
        }
    }

    if (identifiers == 0) {
        problems += "entity must declare at least one id field"
    }
    if (embeddedIdentifiers > 0 && identifiers > 1) {
        problems += "embeddedId cannot be combined with other id fields"
    }
    if (identifiers > 1 && entity.idClass == null) {
        problems += "composite identifier requires idClass"
    }
    if (identifiers > 1 && entity.idClass != null && entity.idClass.trim().isEmpty()) {
        problems += "idClass must not be empty"
    }
    if (generatedIdentifiers > 0 && identifiers > 1) {
        problems += "generated value is only valid on a single id field"
    }
    if (versions > 1) {
        problems += "entity must declare at most one version field"
    }

    for (relation in entity.relations) {
        if (relation.name.trim().isEmpty()) {
            problems += "relation name must not be empty"
            continue
        }
        if (!names.add(relation.name)) {
            problems += "duplicate property name: ${relation.name}"
        }
        if (relation.target.name.trim().isEmpty()) {
            problems += "relation ${relation.name} has an empty target"
        }
        relation.cascade?.let { cascade -> problems += validateCascade(relation.name, cascade) }
        relation.joinTable?.let { joinTable -> problems += validateJoinTable(relation.name, joinTable) }
    }

    entity.discriminatorColumn?.let { discriminator ->
        if (discriminator.column.name == null) {
            problems += "discriminator column must declare a name"
        }
        problems += validateColumn(discriminator.column.name ?: "discriminator", discriminator.column)
    }

    return problems
}

/**
 * Assert that entity metadata is valid.
 *
 * @return the same entity when valid.
 * @throws EntityMetadataError when any structural rule is violated.
 */
fun defineEntity(entity: Entity): Entity {
    val problems = validateEntity(entity)
    if (problems.isNotEmpty()) {
        throw EntityMetadataError(entity.name, problems)
    }
    return entity
}

/**
 * Derive the compact [TableMeta] view of an entity.
 *
 * Fields without an explicit column mapping use their field name; relations are
 * not columns and are omitted.
 *
 * @throws EntityMetadataError when no identifier is declared.
 */
fun toTableMeta(entity: Entity): TableMeta {
    val identifier = entity.fields.firstOrNull { it.id == true }
        ?: throw EntityMetadataError(entity.name, listOf("entity has no id field"))
    return TableMeta(
        table = entity.table?.name ?: throw EntityMetadataError(entity.name, listOf("entity must declare a table")),
        primaryKey = identifier.column?.name ?: identifier.name,
        columns = entity.fields.map { field -> field.column?.name ?: field.name },
    )
}

/** Build a cascade declaration; `ALL` must be used alone. */
fun cascade(vararg types: CascadeType): Cascade = Cascade(types.toList())

/**
 * In-memory registry of entity metadata, analogous to a JPA `Metamodel`.
 *
 * It does not own a connection and does not resolve SQL; it enforces unique
 * entity names and validates metadata at registration time.
 */
class EntityRegistry {

    private val entities: MutableMap<EntityName, Entity> = LinkedHashMap()

    /** Register one entity; throws for invalid or duplicate metadata. */
    fun register(entity: Entity): EntityRegistry {
        val problems = validateEntity(entity)
        if (problems.isNotEmpty()) {
            throw EntityMetadataError(entity.name, problems)
        }
        if (entities.containsKey(entity.name)) {
            throw EntityMetadataError(entity.name, listOf("an entity with this name is already registered"))
        }
        entities[entity.name] = entity
        return this
    }

    fun get(name: EntityName): Entity? = entities[name]

    fun has(name: EntityName): Boolean = entities.containsKey(name)

    /** Registered entity names in registration order. */
    fun names(): List<EntityName> = ArrayList(entities.keys)

    fun clear() {
        entities.clear()
    }
}

private fun validateColumn(propertyName: String, column: Column<*>): List<String> {
    val problems = ArrayList<String>()
    column.name?.let { if (it.trim().isEmpty()) problems += "$propertyName has an empty column name" }
    column.table?.let { if (it.trim().isEmpty()) problems += "$propertyName has an empty column table" }
    column.length?.let { if (it <= 0) problems += "$propertyName column length must be a positive integer" }
    column.precision?.let { if (it < 0) problems += "$propertyName column precision must be a non-negative integer" }
    column.scale?.let { if (it < 0) problems += "$propertyName column scale must be a non-negative integer" }
    if (column.precision != null && column.scale != null && column.scale > column.precision) {
        problems += "$propertyName column scale cannot exceed precision"
    }
    return problems
}

private fun validateTable(table: Table): List<String> {
    val problems = ArrayList<String>()
    val indexNames = LinkedHashSet<String>()
    table.indexes.orEmpty().forEach { index ->
        if (index.columnList.trim().isEmpty()) {
            problems += "index columnList must not be empty"
        }
        index.name?.let { name ->
            if (!indexNames.add(name)) {
                problems += "duplicate index name: $name"
            }
        }
    }
    val constraintNames = LinkedHashSet<String>()
    table.uniqueConstraints.orEmpty().forEach { constraint ->
        if (constraint.columnNames.isEmpty()) {
            problems += "unique constraint must contain at least one column"
        }
        constraint.name?.let { name ->
            if (!constraintNames.add(name)) {
                problems += "duplicate unique constraint name: $name"
            }
        }
    }
    return problems
}

private fun validateCascade(relationName: String, cascade: Cascade): List<String> {
    val problems = ArrayList<String>()
    if (cascade.types.isEmpty()) {
        problems += "relation $relationName declares an empty cascade set"
    }
    if (CascadeType.ALL in cascade.types && cascade.types.size > 1) {
        problems += "relation $relationName cannot combine all with other cascades"
    }
    val seen = LinkedHashSet<CascadeType>()
    cascade.types.forEach { type ->
        if (!seen.add(type)) {
            problems += "relation $relationName repeats cascade ${type.wire}"
        }
    }
    return problems
}

private fun validateJoinTable(relationName: String, joinTable: JoinTable): List<String> {
    val problems = ArrayList<String>()
    if (joinTable.name.trim().isEmpty()) {
        problems += "relation $relationName has an empty join table name"
    }
    if (joinTable.joinColumns != null && joinTable.joinColumns.isEmpty()) {
        problems += "relation $relationName join table has no join columns"
    }
    if (joinTable.inverseJoinColumns != null && joinTable.inverseJoinColumns.isEmpty()) {
        problems += "relation $relationName join table has no inverse join columns"
    }
    return problems
}
