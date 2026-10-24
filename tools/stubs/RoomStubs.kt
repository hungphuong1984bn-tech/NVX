package androidx.room
@Target(AnnotationTarget.CLASS) annotation class Entity
@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.CLASS) annotation class Database(val entities: Array<kotlin.reflect.KClass<*>>, val version: Int, val exportSchema: Boolean = true)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) annotation class Insert
@Target(AnnotationTarget.FUNCTION) annotation class Upsert
@Target(AnnotationTarget.FUNCTION) annotation class Update
@Target(AnnotationTarget.FUNCTION) annotation class Delete
abstract class RoomDatabase
object Room {
    class Builder<T : RoomDatabase> { fun build(): T = throw NotImplementedError() }
    fun <T : RoomDatabase> databaseBuilder(c: android.content.Context, k: Class<T>, n: String) = Builder<T>()
}
