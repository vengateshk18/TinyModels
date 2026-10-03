package com.example.tinymodels.domain.usecase.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages download lifecycle to ensure only one download runs at a time.
 * Prevents conflicts and ensures predictable download behavior.
 */
@Singleton
class DownloadManager @Inject constructor() : DownloadManagerService {
    private val _activeDownloads = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloads: StateFlow<Set<String>> = _activeDownloads.asStateFlow()
    
    private val _downloadQueue = MutableStateFlow<List<DownloadRequest>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadRequest>> = _downloadQueue.asStateFlow()
    
    /**
     * Check if a download is currently active for a specific model.
     */
    override fun isActive(modelId: String): Boolean = _activeDownloads.value.contains(modelId)
    
    /**
     * Attempt to start a new download. Returns false if another download is active.
     */
    override fun startDownload(modelId: String, fileName: String?): Boolean {
        synchronized(this) {
            if (_activeDownloads.value.isNotEmpty()) {
                return false
            }
            _activeDownloads.value = setOf(modelId)
            return true
        }
    }
    
    /**
     * Mark a download as complete.
     */
    override fun completeDownload(modelId: String) {
        synchronized(this) {
            _activeDownloads.value -= modelId
        }
    }
    
    /**
     * Cancel an active download.
     */
    override fun cancelDownload(modelId: String) {
        synchronized(this) {
            _activeDownloads.value -= modelId
        }
    }
    
    /**
     * Queue a download request for later execution.
     */
    fun queueDownload(request: DownloadRequest) {
        synchronized(this) {
            _downloadQueue.value += request
        }
    }
    
    /**
     * Get the next queued download request (if any).
     */
    fun dequeueNext(): DownloadRequest? {
        synchronized(this) {
            if (_downloadQueue.value.isEmpty()) return null
            val request = _downloadQueue.value.first()
            _downloadQueue.value = _downloadQueue.value.drop(1)
            return request
        }
    }
    
    /**
     * Check if any download is currently active.
     */
    override fun hasActiveDownload(): Boolean = _activeDownloads.value.isNotEmpty()
    
    /**
     * Get the count of queued downloads.
     */
    fun queuedCount(): Int = _downloadQueue.value.size
    
    data class DownloadRequest(
        val modelId: String,
        val fileName: String?,
        val fileSize: Long
    )
}
