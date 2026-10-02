package com.example.tinymodels.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedModelDao {

    @Query("SELECT * FROM downloaded_models ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadedModelEntity>>

    @Query("SELECT * FROM downloaded_models WHERE modelId = :modelId")
    suspend fun getById(modelId: String): DownloadedModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: DownloadedModelEntity)

    /** Insert only if a row for the modelId doesn't already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: DownloadedModelEntity)

    @Query("DELETE FROM downloaded_models WHERE modelId = :modelId")
    suspend fun delete(modelId: String)
}

