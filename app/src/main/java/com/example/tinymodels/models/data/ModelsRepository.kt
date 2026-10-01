package com.example.tinymodels.models.data

import com.example.tinymodels.utils.Result
class ModelsRepository {
    companion object{
        var INSTANCE: ModelsRepository?=null

        var remoteRepo: ModelsRemoteRepository?=null

        fun getInstance(): ModelsRepository{
            if(INSTANCE==null){
                INSTANCE= ModelsRepository()
                remoteRepo= ModelsRemoteRepository()
            }
            return INSTANCE!!
        }
    }

    suspend fun getListOfModels(): Result<List<Model>>{
        val remoteRes = remoteRepo?.listModels() ?: return Result.Failure("UnKnown error")
        return when(remoteRes){
            is Result.Success -> Result.Success(remoteRes.data)
            is Result.Failure -> Result.Failure(remoteRes.str)
        }
    }
}