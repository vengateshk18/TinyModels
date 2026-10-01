package com.example.tinymodels.models.data

import com.example.tinymodels.utils.OkHttpUtil
import com.example.tinymodels.utils.Result
import com.example.tinymodels.utils.TinyModelsApiConstants
import com.example.tinymodels.utils.UrlGeneratorUtil
import org.json.JSONObject

class ModelDetailsRemoteRepository {
    suspend fun getModelDetails(modelId: String): Result<ModelDetails> {
        return try {
            val url = UrlGeneratorUtil.getUrl(
                TinyModelsApiConstants.MODEL_DETAILS,
                mapOf("modelId" to modelId)
            )
            val response = OkHttpUtil.get(url)
            Result.Success(ModelDetailsParser.parse(JSONObject(response)))
        } catch (exception: Exception) {
            Result.Failure(exception.message ?: "Unable to load model details")
        }
    }
}
