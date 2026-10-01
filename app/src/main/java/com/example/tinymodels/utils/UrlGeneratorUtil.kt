package com.example.tinymodels.utils

object UrlGeneratorUtil {
    private const val apiDomain = "https://huggingface.co/api/models"
    private const val hubDomain = "https://huggingface.co"

    fun getUrl(constant: Int, params: Map<String, String> = emptyMap()): String {
        when(constant){
            TinyModelsApiConstants.LIST_MODELS->{
                return buildUrl(apiDomain, params)
            }
            TinyModelsApiConstants.MODEL_DETAILS -> {
                return "$apiDomain/${params.getValue("modelId")}"
            }
            TinyModelsApiConstants.MODEL_FILE -> {
                return "$hubDomain/${params.getValue("modelId")}/resolve/main/${params.getValue("fileName")}?download=true"
            }
            else -> ""
        }
        return ""
    }

    private fun buildUrl(domain: String, map: Map<String, String>): String {
        if (map.isEmpty()) return domain
        val url = StringBuilder(domain)
        url.append("?")
        for(key in map.keys){
            if(url[url.length-1]!='?'){
                url.append("&")
            }
            url.append("${key}=${map[key]}")
        }
        return url.toString()
    }
}