package com.ai.tinymodels.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.tinymodels.core.database.entities.ModelFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelFileDao {

    @Query("SELECT * FROM model_files WHERE modelId = :modelId")
    fun observeByModelId(modelId: String): Flow<List<ModelFileEntity>>

    @Query("SELECT * FROM model_files WHERE status = 'DOWNLOADED'")
    fun observeDownloaded(): Flow<List<ModelFileEntity>>

    @Query("SELECT * FROM model_files WHERE status = 'PAUSED'")
    fun observePaused(): Flow<List<ModelFileEntity>>

    @Query("SELECT * FROM model_files WHERE modelId = :modelId AND fileName = :fileName")
    suspend fun getByModelIdAndFile(modelId: String, fileName: String): ModelFileEntity?

    /**
     * The best downloaded model file for [modelId].
     *
     * Vision projectors (`mmproj`) share the `.gguf` extension but are not
     * runnable models, so real model files are ordered first. If only a
     * projector is present it is returned last (callers surface a clear
     * "this is a projector" message instead of attempting a native load).
     */
    @Query(
        """
        SELECT * FROM model_files
        WHERE modelId = :modelId AND status = 'DOWNLOADED'
        ORDER BY
            CASE
                WHEN LOWER(fileName) LIKE '%mmproj%' OR LOWER(fileName) LIKE '%projector%'
                THEN 1 ELSE 0
            END,
            sizeBytes DESC
        LIMIT 1
        """
    )
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
    ): Int

    @Query("DELETE FROM model_files WHERE modelId = :modelId AND fileName = :fileName")
    suspend fun delete(modelId: String, fileName: String)

    @Query("DELETE FROM model_files WHERE modelId = :modelId")
    suspend fun deleteAllForModel(modelId: String)

    @Query("SELECT * FROM model_files WHERE modelId = :modelId")
    suspend fun getByModelId(modelId: String): List<ModelFileEntity>

    @Query("SELECT * FROM model_files")
    suspend fun getAll(): List<ModelFileEntity>
}
