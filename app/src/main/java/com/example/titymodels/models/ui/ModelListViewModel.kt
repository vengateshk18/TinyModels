package com.example.titymodels.models.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.titymodels.models.data.Model
import com.example.titymodels.models.domain.ListModels
import com.example.titymodels.utils.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.collections.listOf


class ModelListViewModel(val listModels: ListModels): ViewModel(){

    private val _modelList= MutableStateFlow<List<Model>>(listOf())
    val modelList: StateFlow<List<Model>> = _modelList

    private val _modelListFailure= MutableStateFlow<String>("")
    val modelListFailure: StateFlow<String> = _modelListFailure

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun getListOfModels(){
        viewModelScope.launch {
            _isLoading.value = true
            when(val result=listModels()){
                is Result.Success-> _modelList.value=result.data
                is Result.Failure -> _modelListFailure.value=result.str
            }
            _isLoading.value = false
        }
    }

}