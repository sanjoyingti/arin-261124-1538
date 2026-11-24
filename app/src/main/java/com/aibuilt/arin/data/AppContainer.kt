package com.aibuilt.arin.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibuilt.arin.viewmodel.ArinViewModel

class ArinApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val repositories = Repositories(app)
    val vmFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ArinViewModel::class.java)) {
                "Unknown ViewModel ${modelClass.name}"
            }
            return ArinViewModel(repositories) as T
        }
    }
}
