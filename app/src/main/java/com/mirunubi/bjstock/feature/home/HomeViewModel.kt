package com.mirunubi.bjstock.feature.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.error.SafeLogText
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val loader: HomeReadModelLoader,
) : ViewModel() {
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var loading: Job? = null

    /** Reloads Home. Existing content stays visible while refreshing; only the first load shows Loading. */
    fun refresh() {
        if (loading?.isActive == true) return
        if (_uiState.value is HomeUiState.Error) _uiState.value = HomeUiState.Loading
        loading = viewModelScope.launch {
            _uiState.value = try {
                HomePresenter.present(loader.load())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure(failure)
                HomeUiState.Error(HomePresenter.LOAD_FAILED)
            }
        }
    }

    private fun logFailure(failure: Exception) {
        val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
        Log.w(TAG, "Home load failed ($type)")
    }

    private companion object {
        const val TAG = "BJStockHome"
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class HomeModule {
    @Binds
    abstract fun bindHomeReadModelLoader(loader: RoomHomeReadModelLoader): HomeReadModelLoader
}
