package com.example.tinymodels.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tinymodels.core.database.entities.ModelFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelFileDao {

    @Query("SELECT * FROM model_files WHERE modelId = :modelId")
    fun observeByModelId(modelId: String): Flow<List<ModelFileEntity>>

    @Query("SELECT * FROM model_files WHERE status = 'DOWNLOADED'")
    fun observeDownloaded(): Flow<List<ModelFileEntity>>

    @Query("SELECT * FROM model_files WHERE modelId = :modelId AND fileName = :fileName")
    suspend fun getByModelIdAndFile(modelId: String, fileName: String): ModelFileEntity?

    @Query("SELECT * FROM model_files WHERE modelId = :modelId AND status = 'DOWNLOADED' LIMIT 1")
    suspend fun getFirstDownloadedForModel(modelId: String): ModelFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ModelFileEntity)

    @Query(
        """
        UPDATE model_files
        SET status = :status,
            sizeBytes = COALESCE(:sizeBytes, sizeBytes),
            localPath = COALESCE(:localPath, localPath),
            error = :error
        WHERE modelId = :modelId AND fileName = :fileName
        """
    )
    suspend fun updateStatus(
        modelId: String,
        fileName: String,
        status: String,
        sizeBytes: Long?,
        localPath: String?,
        error: String?
    )

    @Query("DELETE FROM model_files WHERE modelId = :modelId AND fileName = :fileName")
    suspend fun delete(modelId: String, fileName: String)

    @Query("DELETE FROM model_files WHERE modelId = :modelId")
    suspend fun deleteAllForModel(modelId: String)
}
