package com.example.titymodels.utils

sealed class Result<out T> {
    data class Success<out T>(val data:T): Result<T>()
    data class Failure(val str:String): Result<Nothing>()
}