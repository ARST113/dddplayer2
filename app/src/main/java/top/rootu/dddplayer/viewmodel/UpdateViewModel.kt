package top.rootu.dddplayer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.logic.UpdateChannel
import top.rootu.dddplayer.logic.UpdateInfo
import top.rootu.dddplayer.logic.UpdateManager
import top.rootu.dddplayer.utils.getString

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val updateManager = UpdateManager(application)

    private val _updateInfo = MutableLiveData<UpdateInfo?>()
    val updateInfo: LiveData<UpdateInfo?> = _updateInfo

    private val _downloadProgress = MutableLiveData<Int>()
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _isCheckingUpdates = MutableLiveData(false)
    val isCheckingUpdates: LiveData<Boolean> = _isCheckingUpdates

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    init {
        checkUpdatesAuto()
    }

    private fun checkUpdatesAuto() {
        viewModelScope.launch {
            val currentVersion = BuildConfig.VERSION_NAME

            val info = updateManager.checkForUpdates(currentVersion, UpdateChannel.STABLE)
            _updateInfo.postValue(info)
        }
    }

    fun forceCheckUpdates() {
        if (_isCheckingUpdates.value == true) return
        _isCheckingUpdates.value = true

        viewModelScope.launch {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                val info = updateManager.checkForUpdates(currentVersion, UpdateChannel.STABLE)

                if (info != null) {
                    _updateInfo.postValue(info)
                                        _toastMessage.postValue(getString(R.string.update_found, info.version))
                } else {
                                        _updateInfo.postValue(null)
                    _toastMessage.postValue(getString(R.string.update_latest))
                }
                
            } catch (_: Exception) {
                _toastMessage.postValue(getString(R.string.update_error))
            } finally {
                _isCheckingUpdates.postValue(false)
            }
        }
    }

    fun startUpdate() {
        val info = _updateInfo.value ?: return
        viewModelScope.launch {
            val file = updateManager.downloadApk(info) { progress ->
                _downloadProgress.postValue(progress)
            }
            if (file != null) {
                updateManager.installApk(file)
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}