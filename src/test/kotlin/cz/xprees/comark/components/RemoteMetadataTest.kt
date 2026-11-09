package cz.xprees.comark.components

import com.intellij.testFramework.PlatformTestUtil
import com.sun.net.httpserver.HttpServer
import cz.xprees.comark.meta.ComarkComponent
import cz.xprees.comark.meta.ComarkRemoteMetadata
import cz.xprees.comark.meta.ComponentSource
import cz.xprees.comark.settings.ComarkSettings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class RemoteMetadataTest : ComponentsTestBase() {
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()

    @Volatile
    private var status = 200

    @Volatile
    private var body = """
        [
          {
            "mdc_name": "page-hero",
            "description": "Big hero",
            "docs_url": "https://example.com/hero",
            "component_meta": { "meta": { "props": [ { "name": "headline", "type": "string", "required": true } ], "slots": [] } }
          },
          {
            "mdc_name": "alert",
            "description": "Alert from the design system",
            "component_meta": { "meta": { "props": [ { "name": "variant", "type": "'soft' | 'solid'" } ], "slots": [] } }
          }
        ]
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/meta") { exchange ->
            requests.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun tearDown() {
        try {
            server.stop(0)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun url() = "http://${server.address.hostString}:${server.address.port}/meta"

    /** Waits (without blocking the background fetch) until [condition] holds for the component index. */
    private fun awaitIndex(condition: (List<ComarkComponent>) -> Boolean): List<ComarkComponent> {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val components = index().components
            if (condition(components)) return components
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        fail("remote metadata did not arrive; requests=${requests.get()}, components=${index().components}")
        throw IllegalStateException()
    }

    fun testRemoteComponentsAreFetchedInTheBackground() {
        addStandardComponents()
        ComarkSettings.getInstance().componentMetadataUrl = url()
        val components = awaitIndex { list -> list.any { it.name == "PageHero" } }

        val hero = components.first { it.name == "PageHero" }
        assertEquals(ComponentSource.REMOTE, hero.source)
        assertEquals("https://example.com/hero", hero.docsUrl)
        assertTrue(hero.findProp("headline")!!.required)

        // the remote description of a local component wins, but the component keeps its file
        val alert = components.first { it.name == "Alert" }
        assertEquals(ComponentSource.REMOTE, alert.source)
        assertEquals("Alert from the design system", alert.description)
        assertEquals("Alert.vue", alert.file?.name)
        assertEquals(listOf("variant"), alert.props.map { it.name })
    }

    fun testResultIsCachedForTheConfiguredTime() {
        ComarkSettings.getInstance().componentMetadataUrl = url()
        ComarkSettings.getInstance().componentMetadataCacheMinutes = 30
        awaitIndex { list -> list.any { it.name == "PageHero" } }
        val fetched = requests.get()
        repeat(5) { index() }
        assertEquals(fetched, requests.get())
    }

    fun testFailuresAreQuiet() {
        status = 500
        body = "oops"
        ComarkSettings.getInstance().componentMetadataUrl = url()
        index()
        val deadline = System.currentTimeMillis() + 20_000
        while ((requests.get() == 0 || ComarkRemoteMetadata.getInstance().isLoading) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(requests.get() > 0)
        assertTrue(index().components.none { it.source == ComponentSource.REMOTE })
    }

    fun testOnlyHttpUrlsAreFetched() {
        assertFalse(ComarkRemoteMetadata.isFetchable("file:///etc/passwd"))
        assertFalse(ComarkRemoteMetadata.isFetchable("ftp://example.com/meta.json"))
        assertTrue(ComarkRemoteMetadata.isFetchable("https://example.com/meta.json"))
        assertTrue(ComarkRemoteMetadata.isFetchable("HTTP://localhost:3000/api/component-meta"))
    }
}
