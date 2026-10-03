package com.example.tinymodels.domain.usecase.model

/**
 * Interface for download lifecycle management.
 */
interface DownloadManagerService {
    /**
     * Check if a download is currently active for a specific model.
     */
    fun isActive(modelId: String): Boolean
    
    /**
     * Attempt to start a new download. Returns false if another download is active.
     */
    fun startDownload(modelId: String, fileName: String? = null): Boolean
    
    /**
     * Mark a download as complete.
     */
    fun completeDownload(modelId: String)
    
    /**
     * Cancel an active download.
     */
    fun cancelDownload(modelId: String)
    
    /**
     * Check if any download is currently active.
     */
    fun hasActiveDownload(): Boolean
}
