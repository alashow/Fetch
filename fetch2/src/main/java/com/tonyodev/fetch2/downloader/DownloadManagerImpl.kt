package com.tonyodev.fetch2.downloader

import android.os.Handler
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2.exception.FetchException
import com.tonyodev.fetch2.helper.DownloadInfoUpdater
import com.tonyodev.fetch2.helper.FileDownloaderDelegate
import com.tonyodev.fetch2.fetch.ListenerCoordinator
import com.tonyodev.fetch2.provider.NetworkInfoProvider
import com.tonyodev.fetch2.util.getRequestForDownload
import com.tonyodev.fetch2core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DownloadManagerImpl(private val httpDownloader: Downloader,
                          concurrentLimit: Int,
                          private val progressReportingIntervalMillis: Long,
                          private val logger: Logger,
                          private val networkInfoProvider: NetworkInfoProvider,
                          private val retryOnNetworkGain: Boolean,
                          private val uiHandler: Handler,
                          private val downloadInfoUpdater: DownloadInfoUpdater,
                          private val fileTempDir: String,
                          private val downloadManagerCoordinator: DownloadManagerCoordinator,
                          private val listenerCoordinator: ListenerCoordinator,
                          private val fileServerDownloader: FileServerDownloader,
                          private val md5CheckingEnabled: Boolean) : DownloadManager {

    private val lock = Any()
    private var executor: ExecutorService? = getNewDownloadExecutorService(concurrentLimit)
    @Volatile
    override var concurrentLimit: Int = concurrentLimit
        set(value) {
            synchronized(lock) {
                try {
                    getActiveDownloadsIds().forEach { id ->
                        cancelDownloadNoLock(id)
                    }
                } catch (e: Exception) {
                }
                try {
                    executor?.shutdown()
                } catch (e: Exception) {
                }
                executor = getNewDownloadExecutorService(concurrentLimit)
                field = value
            }
        }
    private val currentDownloadsMap = hashMapOf<Int, FileDownloader?>()
    @Volatile
    private var downloadCounter = 0
    @Volatile
    private var closed = false
    override val isClosed: Boolean
        get() {
            return closed
        }

    override fun start(download: Download): Boolean {
        return synchronized(lock) {
            throwExceptionIfClosed()
            if (currentDownloadsMap.containsKey(download.id)) {
                logger.d("DownloadManager already running download $download")
                return false
            }
            if (downloadCounter >= concurrentLimit) {
                logger.d("DownloadManager cannot init download $download because " +
                        "the download queue is full")
                return false
            }
            downloadCounter += 1
            currentDownloadsMap[download.id] = null
            downloadManagerCoordinator.addFileDownloader(download.id, null)
            val downloadExecutor = executor
            if (downloadExecutor != null && !downloadExecutor.isShutdown) {
                downloadExecutor.execute {
                    val fileDownloader = getNewFileDownloaderForDownload(download)
                    val runDownload = synchronized(lock) {
                        if (currentDownloadsMap.containsKey(download.id)) {
                            fileDownloader.delegate = getFileDownloaderDelegate()
                            currentDownloadsMap[download.id] = fileDownloader
                            downloadManagerCoordinator.addFileDownloader(download.id, fileDownloader)
                            logger.d("DownloadManager starting download $download")
                            true
                        } else {
                            false
                        }
                    }
                    if (runDownload) {
                        fileDownloader.run()
                    }
                    synchronized(lock) {
                        if (currentDownloadsMap.containsKey(download.id)) {
                            currentDownloadsMap.remove(download.id)
                            downloadCounter -= 1
                        }
                        downloadManagerCoordinator.removeFileDownloader(download.id)
                    }
                }
                true
            } else {
                false
            }
        }
    }

    override fun cancel(downloadId: Int): Boolean {
        return synchronized(lock) {
            cancelDownloadNoLock(downloadId)
        }
    }

    private fun cancelDownloadNoLock(downloadId: Int): Boolean {
        throwExceptionIfClosed()
        return if (currentDownloadsMap.containsKey(downloadId)) {
            val fileDownloader = currentDownloadsMap[downloadId]
            fileDownloader?.interrupted = true
            currentDownloadsMap.remove(downloadId)
            downloadCounter -= 1
            downloadManagerCoordinator.removeFileDownloader(downloadId)
            if (fileDownloader != null) {
                logger.d("DownloadManager cancelled download ${fileDownloader.download}")
            }
            true
        } else {
            downloadManagerCoordinator.interruptDownload(downloadId)
            false
        }
    }

    override fun cancelAll() {
        synchronized(lock) {
            throwExceptionIfClosed()
            cancelAllDownloads()
        }
    }

    private fun cancelAllDownloads() {
        downloadManagerCoordinator.getFileDownloaderList().iterator().forEach {
            val fileDownloader = it
            if (fileDownloader != null) {
                fileDownloader.interrupted = true
                downloadManagerCoordinator.removeFileDownloader(fileDownloader.download.id)
                logger.d("DownloadManager cancelled download ${fileDownloader.download}")
            }
        }
        currentDownloadsMap.clear()
        downloadCounter = 0
    }

    private fun terminateAllDownloads() {
        currentDownloadsMap.iterator().forEach {
            val fileDownloader = it.value
            if (fileDownloader != null) {
                fileDownloader.terminated = true
                logger.d("DownloadManager terminated download ${fileDownloader.download}")
                downloadManagerCoordinator.removeFileDownloader(it.key)
            }
        }
        currentDownloadsMap.clear()
        downloadCounter = 0
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            terminateAllDownloads()
            logger.d("DownloadManager closing download manager")
            executor?.shutdown()
        }
    }

    override fun contains(downloadId: Int): Boolean {
        synchronized(lock) {
            return !isClosed && downloadManagerCoordinator.containsFileDownloader(downloadId)
        }
    }

    override fun canAccommodateNewDownload(): Boolean {
        synchronized(lock) {
            return !closed && downloadCounter < concurrentLimit
        }
    }

    override fun getActiveDownloadCount(): Int {
        synchronized(lock) {
            throwExceptionIfClosed()
            return downloadCounter
        }
    }

    override fun getActiveDownloads(): List<Download?> {
        synchronized(lock) {
            throwExceptionIfClosed()
            return currentDownloadsMap.values.map { it?.download }
        }
    }

    override fun getActiveDownloadsIds(): List<Int> {
        synchronized(lock) {
            throwExceptionIfClosed()
            return currentDownloadsMap.keys.toList()
        }
    }

    private fun throwExceptionIfClosed() {
        if (closed) {
            throw FetchException("DownloadManager is already shutdown.")
        }
    }

    override fun getNewFileDownloaderForDownload(download: Download): FileDownloader {
        return if (!isFetchFileServerUrl(download.url)) {
            getFileDownloader(download, httpDownloader)
        } else {
            getFileDownloader(download, fileServerDownloader)
        }
    }

    private fun getFileDownloader(download: Download, downloader: Downloader): FileDownloader {
        val request = getRequestForDownload(download)
        val supportedDownloadTypes = downloader.getRequestSupportedFileDownloaderTypes(request)
        return if (downloader.getRequestFileDownloaderType(request, supportedDownloadTypes) == Downloader.FileDownloaderType.SEQUENTIAL) {
            SequentialFileDownloaderImpl(
                    initialDownload = download,
                    downloader = downloader,
                    progressReportingIntervalMillis = progressReportingIntervalMillis,
                    logger = logger,
                    networkInfoProvider = networkInfoProvider,
                    retryOnNetworkGain = retryOnNetworkGain,
                    md5CheckingEnabled = md5CheckingEnabled)
        } else {
            val tempDir = downloader.getDirectoryForFileDownloaderTypeParallel(request)
                    ?: fileTempDir
            ParallelFileDownloaderImpl(
                    initialDownload = download,
                    downloader = downloader,
                    progressReportingIntervalMillis = progressReportingIntervalMillis,
                    logger = logger,
                    networkInfoProvider = networkInfoProvider,
                    retryOnNetworkGain = retryOnNetworkGain,
                    fileTempDir = tempDir,
                    md5CheckingEnabled = md5CheckingEnabled)
        }
    }

    override fun getFileDownloaderDelegate(): FileDownloader.Delegate {
        return FileDownloaderDelegate(
                downloadInfoUpdater = downloadInfoUpdater,
                uiHandler = uiHandler,
                fetchListener = listenerCoordinator.mainListener,
                logger = logger,
                retryOnNetworkGain = retryOnNetworkGain)
    }

    override fun getDownloadFileTempDir(download: Download): String {
        val request = getRequestForDownload(download)
        return if (isFetchFileServerUrl(request.url)) {
            fileServerDownloader.getDirectoryForFileDownloaderTypeParallel(request)
                    ?: fileTempDir
        } else {
            httpDownloader.getDirectoryForFileDownloaderTypeParallel(request)
                    ?: fileTempDir
        }
    }

    private fun getNewDownloadExecutorService(concurrentLimit: Int): ExecutorService? {
        return if (concurrentLimit > 0) {
            Executors.newFixedThreadPool(concurrentLimit)
        } else {
            null
        }
    }

}