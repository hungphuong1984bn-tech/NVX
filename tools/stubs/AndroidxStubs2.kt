package androidx.datastore.preferences.core
import androidx.datastore.core.DataStore
class Preferences { class Key<T>(val name: String); operator fun <T> get(k: Key<T>): T? = null }
class MutablePreferences { operator fun <T> set(k: Preferences.Key<T>, v: T) {}; fun clear() {}; fun <T> remove(k: Preferences.Key<T>) {} }
fun booleanPreferencesKey(n: String) = Preferences.Key<Boolean>(n)
fun intPreferencesKey(n: String) = Preferences.Key<Int>(n)
fun stringPreferencesKey(n: String) = Preferences.Key<String>(n)
suspend fun DataStore<Preferences>.edit(f: (MutablePreferences) -> Unit): Preferences = Preferences()
