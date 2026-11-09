package cz.xprees.comark.meta

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * Component metadata fetched from [cz.xprees.comark.settings.ComarkSettings.componentMetadataUrl].
 *
 * Callers on any thread (completion, highlighting, documentation) get the last fetched result immediately; a missing or expired
 * result only schedules a fetch on a background coroutine, so no read action ever waits for the network. When a fetch finishes,
 * [modificationTracker] changes (dropping cached component indexes) and open editors are re-highlighted. Results are kept for the
 * configured number of minutes; failures are retried after at most a minute. Application level because the URL is an
 * application setting: several open projects share one fetch.
 */
@Service(Service.Level.APP)
class ComarkRemoteMetadata(private val scope: CoroutineScope) {
    private class Snapshot(val url: String, val fetchedAt: Long, val records: List<MetaComponentRecord>, val failed: Boolean)

    @Volatile
    private var snapshot: Snapshot? = null

    /** URL being fetched right now, so concurrent callers don't start a second request. */
    private val inFlight = AtomicReference<String?>()

    /** Changes whenever new remote metadata arrives. */
    val modificationTracker = SimpleModificationTracker()

    /**
     * Components described at [url] as far as known now (empty until the first fetch completes). Schedules a fetch when there is
     * no result for [url] yet or it is older than [cacheMinutes]. Never blocks.
     */
    fun records(url: String, cacheMinutes: Int): List<MetaComponentRecord> {
        if (!isFetchable(url)) return emptyList()
        val current = snapshot?.takeIf { it.url == url }
        if (current == null || isExpired(current, cacheMinutes)) fetch(url)
        return current?.records.orEmpty()
    }

    /** Drops the cached result for [url] and fetches it again. */
    fun refresh(url: String) {
        if (!isFetchable(url)) return
        snapshot?.takeIf { it.url == url }?.let { snapshot = Snapshot(it.url, 0, it.records, it.failed) }
        fetch(url)
    }

    /** True while a fetch is running (used by tests and the refresh action). */
    val isLoading: Boolean get() = inFlight.get() != null

    private fun isExpired(snapshot: Snapshot, cacheMinutes: Int): Boolean {
        val age = System.currentTimeMillis() - snapshot.fetchedAt
        val ttl = cacheMinutes.coerceAtLeast(0) * 60_000L
        val effective = if (snapshot.failed) ttl.coerceAtMost(FAILURE_RETRY_MS) else ttl
        return age >= effective.coerceAtLeast(MIN_REFETCH_MS)
    }

    private fun fetch(url: String) {
        if (!inFlight.compareAndSet(null, url)) return
        scope.launch(Dispatchers.IO) {
            try {
                val text = HttpRequests.request(url)
                    .accept("application/json")
                    .connectTimeout(CONNECT_TIMEOUT_MS)
                    .readTimeout(READ_TIMEOUT_MS)
                    .readString()
                val records = ComponentMetaParser.parse(text)
                if (records == null) {
                    LOG.warn("Comark component metadata from $url is not in a supported format")
                    failed(url)
                } else {
                    snapshot = Snapshot(url, System.currentTimeMillis(), records, failed = false)
                    LOG.info("Comark: ${records.size} components loaded from $url")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Comark: cannot fetch component metadata from $url: ${e.message}")
                failed(url)
            } finally {
                inFlight.set(null)
            }
            modificationTracker.incModificationCount()
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Comark remote component metadata changed")
            }
        }
    }

    /** Keeps the previous result for the URL (stale data beats none) but remembers the failure so it is retried soon. */
    private fun failed(url: String) {
        val previous = snapshot?.takeIf { it.url == url }?.records.orEmpty()
        snapshot = Snapshot(url, System.currentTimeMillis(), previous, failed = true)
    }

    companion object {
        private val LOG = logger<ComarkRemoteMetadata>()
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val FAILURE_RETRY_MS = 60_000L

        /** Even with caching disabled, requests are spaced out: completion asks for metadata on every keystroke. */
        private const val MIN_REFETCH_MS = 10_000L

        fun getInstance(): ComarkRemoteMetadata = service()

        fun isFetchable(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }
}
