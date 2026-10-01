package com.example.titymodels.models.data

import com.example.titymodels.utils.OkHttpUtil
import com.example.titymodels.utils.Result
import com.example.titymodels.utils.TinyModelsApiConstants
import com.example.titymodels.utils.UrlGeneratorUtil
import org.json.JSONArray

class ModelsRemoteRepository {
    companion object{
        var INSTANCE: ModelsRemoteRepository?=null

        fun getInstance(): ModelsRemoteRepository{
            if (INSTANCE==null)
            {
                INSTANCE= ModelsRemoteRepository()
            }
            return INSTANCE!!
        }
    }

    suspend fun listModels(): Result<List<Model>>{
        val params=mapOf<String,String>(
            Pair("author","litert-community"),
            Pair("limit","100"),
            Pair("direction","-1"),
            Pair("sort","downloads"),
            Pair("full","true")
        )
        val url= UrlGeneratorUtil.getUrl(TinyModelsApiConstants.LIST_MODELS,params)
        try{
            val response: String= OkHttpUtil.get(url)
            val jsonArr= JSONArray(response)
            return Result.Success(data= ModelParser.parseModelArray(jsonArr))
        }
        catch (e: Exception){
            return Result.Failure(e.message.toString())
        }
    }
}