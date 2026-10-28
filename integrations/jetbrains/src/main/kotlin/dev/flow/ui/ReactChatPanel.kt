package dev.flow.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.JPanel

/** Bundled local document only. This bridge exposes UI actions, never arbitrary engine RPC. */
internal class ReactChatPanel(
    parentScope: CoroutineScope,
    private val state: () -> JsonObject,
    private val action: suspend (JsonObject) -> JsonElement,
    private val unavailable: () -> Unit,
    private val browser: JBCefBrowser = JBCefBrowser(),
) : JPanel(BorderLayout()),
    Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT)
    private val query = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val token = UUID.randomUUID().toString()
    private var ready = false
    private var disposed = false
    private var lastState: JsonObject? = null
    private val pending = mutableSetOf<Long>()
    private var lastRequest = 0L

    init {
        Disposer.register(this, browser)
        Disposer.register(this, query)
        val client = browser.jbCefClient.cefClient
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean) = request?.url != PAGE
            override fun onOpenURLFromTab(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, userGesture: Boolean) = true
        })
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?) = true
        })
        query.addHandler { text ->
            try {
                val envelope = parse(text, token)
                scope.launch {
                    if (disposed) return@launch
                    val id = envelope["id"].asLong
                    if (id <= lastRequest || pending.size >= 32) {
                        reply(id, null, "Request is stale or the interface is busy. Try again.")
                        return@launch
                    }
                    lastRequest = id
                    pending.add(id)
                    try {
                        if (envelope["action"].asString == "ready") ready = true
                        val result = if (envelope["action"].asString == "ready") state().also { lastState = it } else action(envelope)
                        reply(id, result, null)
                        update()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Only product validation messages; no adapter output, paths or parser dumps.
                        reply(id, null, if (error is IllegalArgumentException || error is IllegalStateException) error.message?.take(500) ?: "Action unavailable" else "Action could not be completed. Your draft is retained.")
                    } finally {
                        pending.remove(id)
                    }
                }
                JBCefJSQuery.Response("queued")
            } catch (_: Exception) {
                JBCefJSQuery.Response(null, 1, "Invalid Flow UI request")
            }
        }
        add(browser.component, BorderLayout.CENTER)
        val bridge = "window.flowPost = function(payload) { const request = JSON.stringify({token: '$token', payload}); ${query.inject("request")} };"
        browser.loadHTML(document(token, bridge), PAGE)
        scope.launch {
            delay(15000)
            if (!disposed && !ready) unavailable()
        }
    }

    fun update() {
        if (!ready || disposed) return
        val value = state()
        val patch = changedState(lastState, value)
        if (patch.size() == 0) return
        lastState = value
        browser.cefBrowser.executeJavaScript("window.flowReceive && window.flowReceive($patch)", PAGE, 0)
    }

    fun find() {
        if (ready && !disposed) browser.cefBrowser.executeJavaScript("window.dispatchEvent(new Event('flow-find'))", PAGE, 0)
    }

    private fun reply(id: Long, result: JsonElement?, error: String?) {
        if (disposed) return
        val reply = JsonObject().apply {
            addProperty("id", id)
            if (error != null) addProperty("error", error) else add("result", result)
        }
        browser.cefBrowser.executeJavaScript("window.flowReply && window.flowReply($reply)", PAGE, 0)
    }

    override fun dispose() {
        disposed = true
        ready = false
        scope.cancel()
    }

    companion object {
        // loadHTML preserves file URLs; HTTP URLs are rewritten to an internal random file URL.
        internal const val PAGE = "file:///flow-chat/index.html"

        /** Ready supplies a full snapshot; later updates replace changed top-level fields only. */
        internal fun changedState(previous: JsonObject?, current: JsonObject) = JsonObject().apply {
            current.entrySet().forEach { (key, value) -> if (previous?.get(key) != value) add(key, value) }
            previous?.keySet()?.filterNot(current::has)?.forEach { add(it, com.google.gson.JsonNull.INSTANCE) }
        }
        internal fun parse(text: String, token: String): JsonObject {
            require(text.length <= 12 * 1024 * 1024)
            val envelope = JsonParser.parseString(text).asJsonObject
            require(envelope["token"].asString == token)
            val payload = envelope.getAsJsonObject("payload")
            require(payload["id"].asJsonPrimitive.isNumber && payload["id"].asString.matches(Regex("[1-9][0-9]{0,8}")))
            require(payload["action"].asString in ACTIONS)
            return payload
        }
        internal val ACTIONS = setOf("ready", "draft", "promptHistory", "send", "stop", "provider", "model", "configure", "mode", "settings", "history", "review", "new", "attach", "paste", "upload", "removeAttachment", "openAttachment", "removeContext", "context", "enhance", "enhancement", "permission", "authenticate", "queue", "rewind", "quote", "copy", "nativeDiff", "moreChanges", "changesStats", "refreshChanges", "command", "complete", "openReference", "recordedAttachment", "knowledge", "knowledgeOpen")
        internal fun document(nonce: String, bridge: String): String {
            fun resource(name: String) = requireNotNull(ReactChatPanel::class.java.getResourceAsStream("/webview/$name")) { "React chat assets are missing. Rebuild the plugin." }.bufferedReader().use { it.readText() }
            return """<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><style>${resource("chat.css")}</style></head><body><div id="root"></div><script nonce="$nonce">$bridge</script><script nonce="$nonce">${resource("chat.js").replace("</script", "<\\/script", ignoreCase = true)}</script></body></html>"""
        }
    }
}
