package androidx.lifecycle
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
open class ViewModel { protected open fun onCleared() {} }
open class AndroidViewModel(val app: Application) : ViewModel() { fun <T : Application> getApplication(): T { @Suppress("UNCHECKED_CAST") return app as T } }
val ViewModel.viewModelScope: CoroutineScope get() = CoroutineScope(SupervisorJob() + Dispatchers.Main)
