package dev.flow.ui

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import dev.flow.bridge.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Opt-in real JCEF handshake, packaged assets, DOM action and reply smoke. No LLM or network. */
class ReactBrowserSmokeTest : LightPlatformTestCase() {
    fun testBundledReactCanRenderAndCallHost() {
        assertTrue("Run with the IntelliJ JetBrains Runtime containing JCEF", JBCefApp.isSupported())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("react-browser-smoke"), { error("No engine needed") })
        val session = AgentSessionPanel(controller, project)
        session.update(EngineView(connected = true, agentSupported = true))
        val checkpoint = "a".repeat(64)
        var state = session.webState().apply {
            addProperty("sessionId", "diff-smoke")
            add("messages", JsonParser.parseString("""[{"kind":"turn.started","text":"Test the code","turnId":"t1","images":[],"attachments":[]},{"kind":"tool.call","text":"Run unit tests","toolCallId":"run-smoke","turnId":"t1","images":[],"attachments":[],"tool":{"kind":"execute","title":"Run unit tests","status":"completed","input":"cargo test","output":"PASS fixture"}},{"kind":"message.delta","text":"The tests passed. [source](main.rs#L1)","turnId":"t1","images":[],"attachments":[]}]"""))
            add("progressPanels", JsonParser.parseString("""{"plan":{"entries":[{"content":"Inspect smoke source","status":"in_progress","priority":"medium"}],"truncated":false},"subagents":[]}"""))
            addProperty("configReady", true)
            add("configOptions", JsonParser.parseString("""[{"id":"model","name":"Model","category":"model","kind":"select","description":"","currentValue":"smoke-a","options":[{"value":"smoke-a","name":"Smoke A","description":"","group":""},{"value":"smoke-b","name":"Smoke B","description":"","group":""}]}]"""))
            getAsJsonArray("configOptions").add(JsonParser.parseString("""{"id":"effort","name":"Effort","category":"thought_level","kind":"select","description":"","currentValue":"high","options":[{"value":"low","name":"Low","description":"","group":""},{"value":"high","name":"High","description":"","group":""}]}"""))
            add("changes", JsonParser.parseString("""{"snapshot":"$checkpoint","total":1,"note":"Recorded changes","visible":true,"more":false,"files":[{"index":0,"oldPath":"main.rs","newPath":"main.rs","kind":"modified"}]}"""))
        }
        val browser = JBCefBrowser.createBuilder().setOffScreenRendering(true).build()
        var ready = false
        var fallback = false
        var received = ""
        var configured = ""
        var inputHistoryLoaded = false
        var cachedPromptFilled = false
        val panel = ReactChatPanel(
            scope,
            {
                ready = true
                state
            },
            action@{ value ->
                if (value["action"].asString == "promptHistory") {
                    assertEquals("list", value["operation"].asString)
                    inputHistoryLoaded = true
                    return@action JsonParser.parseString("""{"revision":1,"enabled":true,"items":[{"text":"Explain this project.","uses":2,"lastUsed":1}]}""")
                }
                received = value["action"].asString
                if (received == "draft" && value["text"].asString == "Explain this project.") cachedPromptFilled = true
                if (received in setOf("diff", "nativeDiff")) {
                    assertEquals("diff-smoke", value["sessionId"].asString)
                    assertEquals(checkpoint, value["snapshot"].asString)
                    assertEquals(0, value["index"].asInt)
                }
                if (received == "configure") {
                    assertEquals("diff-smoke", value["sessionId"].asString)
                    configured = value["configId"].asString
                    assertEquals(if (configured == "model") "smoke-a" else "high", value["currentValue"].asString)
                    assertEquals(if (configured == "model") "smoke-b" else "low", value["value"].asString)
                    assertEquals("smoke-a", value["modelValue"].asString)
                }
                if (received == "provider") {
                    assertEquals("diff-smoke", value["sessionId"].asString)
                    assertEquals(state["profile"].asString, value["currentProfile"].asString)
                    assertEquals("cursor", value["value"].asString)
                }
                if (received == "enhancement") {
                    assertEquals("diff-smoke", value["sessionId"].asString)
                    assertEquals("review-smoke", value["reviewId"].asString)
                    assertEquals(1, value["generation"].asInt)
                    assertEquals("accept", value["operation"].asString)
                    assertEquals("Explain Main.java without modifying it.", value["output"].asString)
                    assertTrue(value.has("version") && value.has("text"))
                }
                if (received == "copy") {
                    assertEquals("diff-smoke", value["sessionId"].asString)
                    assertEquals(2, value["index"].asInt)
                    assertEquals("t1", value["turnId"].asString)
                }
                if (received == "openReference") assertEquals("main.rs#L1", value["reference"].asString)
                if (received == "knowledgeOpen") {
                    assertEquals("project", value["workspace"].asString)
                    assertEquals("main.rs", value.getAsJsonObject("params")["path"].asString)
                    assertEquals(checkpoint, value.getAsJsonObject("params")["contentHash"].asString)
                }
                if (received == "knowledge") {
                    assertEquals("project", value["workspace"].asString)
                    JsonParser.parseString("""{"freshness":"verifiedAtRead","results":[{"id":"main.rs#0","path":"main.rs","name":"main","contentHash":"$checkpoint","startLine":1,"text":"fn main() {}"}]}""")
                } else if (received == "complete") {
                    assertEquals("/", value["trigger"].asString)
                    JsonParser.parseString("""[{"kind":"command","label":"review","detail":"Native command","insert":"/review "}]""")
                } else if (received == "changesStats") {
                    JsonParser.parseString("""{"snapshot":"$checkpoint","files":[{"index":0,"additions":1,"deletions":1}]}""")
                } else {
                    JsonNull.INSTANCE
                }
            },
            { fallback = true },
            browser,
        )
        try {
            panel.setSize(420, 720)
            panel.doLayout()
            browser.cefBrowser.createImmediately()
            waitUntil { ready || fallback }
            assertTrue("Bundled React must complete its ready handshake", ready && !fallback)
            // DOM click exercises React -> generated JBCefJSQuery -> Kotlin -> reply.
            browser.cefBrowser.executeJavaScript("document.querySelector('button[aria-label=Settings]').click()", ReactChatPanel.PAGE, 0)
            waitUntil { received == "settings" }
            assertEquals("settings", received)
            waitUntil { inputHistoryLoaded }
            browser.cefBrowser.setFocus(true)
            browser.cefBrowser.executeJavaScript(
                """const cachedInput = document.querySelector('textarea');
                cachedInput.focus();
                cachedInput.dispatchEvent(new FocusEvent('focusin', {bubbles: true}));
                Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(cachedInput, 'Explain');
                cachedInput.setSelectionRange(7,7);
                cachedInput.dispatchEvent(new Event('input', {bubbles: true}));
                const cachedCheck = setInterval(() => {
                  if (document.querySelector('.prompt-completion')?.textContent === 'Explain this project.') {
                    clearInterval(cachedCheck);
                    cachedInput.dispatchEvent(new KeyboardEvent('keydown', {key: 'Tab', bubbles: true}));
                  }
                }, 20);
                setTimeout(() => clearInterval(cachedCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { cachedPromptFilled }
            browser.cefBrowser.executeJavaScript(
                """document.querySelector('summary[aria-label="Run unit tests · Done"]').click();
                const toolCheck = setInterval(() => {
                  if (Array.from(document.querySelectorAll('.tool-details pre')).some(item => item.textContent === 'PASS fixture')) {
                    clearInterval(toolCheck);
                    document.querySelector('article.assistant button[aria-label="Copy message"]').click();
                  }
                }, 20);
                setTimeout(() => clearInterval(toolCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "copy" }
            received = ""
            browser.cefBrowser.executeJavaScript(
                """document.querySelector('button.agent-trigger').click();
                const agentCheck = setInterval(() => {
                  const cursor = Array.from(document.querySelectorAll('[role="menuitemradio"]')).find(item => item.textContent.startsWith('Cursor'));
                  if (cursor) {
                    clearInterval(agentCheck);
                    cursor.click();
                  }
                }, 20);
                setTimeout(() => clearInterval(agentCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "provider" }
            received = ""
            state = state.deepCopy().apply { addProperty("model", "Updated by a partial state patch") }
            panel.update()
            browser.cefBrowser.executeJavaScript(
                """const patchCheck = setInterval(() => {
                  if (document.querySelector('button.model .model-name')?.textContent === 'Updated by a partial state patch') {
                    clearInterval(patchCheck);
                    document.querySelector('button[aria-label=Settings]').click();
                  }
                }, 20);
                setTimeout(() => clearInterval(patchCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "settings" }
            received = ""
            browser.cefBrowser.executeJavaScript(
                """document.querySelector('button.model').click();
                const modelCheck = setInterval(() => {
                  document.querySelector('button.model-config-row')?.click();
                  const choices = document.querySelectorAll('.model-list > .model-option');
                  if (choices.length === 2) {
                    clearInterval(modelCheck);
                    choices[1].click();
                  }
                }, 20);
                setTimeout(() => clearInterval(modelCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "configure" && configured == "model" }
            browser.cefBrowser.executeJavaScript(
                """let effortOpened = false;
                const effortCheck = setInterval(() => {
                  if (!effortOpened) {
                    if (document.querySelector('.model-popover')) return;
                    document.querySelector('button.model').click();
                    effortOpened = true;
                    return;
                  }
                  const row = document.querySelector('.effort-slider [role="slider"]');
                  if (row) {
                    clearInterval(effortCheck);
                    row.focus();
                    row.dispatchEvent(new KeyboardEvent('keydown', {key: 'Home', bubbles: true}));
                  }
                }, 20);
                setTimeout(() => clearInterval(effortCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "configure" && configured == "effort" }
            browser.cefBrowser.executeJavaScript(
                """document.querySelector('button[aria-label="Show edits"]').click();
                const diffCheck = setInterval(() => {
                  const file = document.querySelector('button[aria-label="Open diff for main.rs"]');
                  if (file) {
                    clearInterval(diffCheck);
                    if (document.querySelector('.diff-line')) throw new Error('Diffs must open in the IDE');
                    file.click();
                  }
                }, 20);
                setTimeout(() => clearInterval(diffCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "nativeDiff" }
            assertEquals("nativeDiff", received)
            received = ""
            browser.cefBrowser.executeJavaScript(
                """document.querySelector('button[aria-label="Show plan"]').click();
                const planCheck = setInterval(() => {
                  const panel = document.querySelector('.progress-popover');
                  if (panel?.textContent.includes('Inspect smoke source')) {
                    clearInterval(planCheck);
                    panel.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true}));
                    const input = document.querySelector('textarea[aria-label="Message"]');
                    input.focus();
                    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, '/r');
                    input.setSelectionRange(2,2);
                    input.dispatchEvent(new Event('input', {bubbles:true}));
                  }
                },20);
                setTimeout(() => clearInterval(planCheck),10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "complete" }
            received = ""
            browser.cefBrowser.executeJavaScript(
                """const annotationCheck = setInterval(() => {
                  const option = document.querySelector('.completion-list button');
                  if (option) {
                    clearInterval(annotationCheck);
                    option.click();
                    const file = Array.from(document.querySelectorAll('button.file-reference')).find(item=>item.textContent==='source');
                    file.click();
                  }
                },20);
                setTimeout(() => clearInterval(annotationCheck),10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "openReference" }
            received = ""
            browser.cefBrowser.executeJavaScript(
                """document.querySelector('button[aria-label="Add attachments and context"]').click();
                const codeMenuCheck = setInterval(() => {
                  const item = Array.from(document.querySelectorAll('[role="menuitem"]')).find(item => item.textContent.includes('Code map & learnings'));
                  if (item) { clearInterval(codeMenuCheck); item.click(); }
                }, 20);
                setTimeout(() => clearInterval(codeMenuCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "knowledge" }
            received = ""
            browser.cefBrowser.executeJavaScript(
                """const codeSourceCheck = setInterval(() => {
                  const source = document.querySelector('dialog.code-explorer[open] button[aria-label="Open main.rs"]');
                  if (source) { clearInterval(codeSourceCheck); source.click(); }
                }, 20);
                setTimeout(() => clearInterval(codeSourceCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "knowledgeOpen" }
            browser.cefBrowser.executeJavaScript("document.querySelector('button[aria-label=\"Close code explorer\"]').click()", ReactChatPanel.PAGE, 0)
            state = state.deepCopy().apply {
                addProperty("enhancementState", "reviewing")
                add("enhancement", JsonParser.parseString("""{"id":"review-smoke","generation":1,"status":"ready","original":"Explain Main.java","output":"Explain Main.java without modifying it.","selectedProvider":"automatic","provider":"codex","model":"smoke-a","claudeModel":"","codexModel":"","chatProvider":"codex","error":""}"""))
            }
            panel.update()
            received = ""
            browser.cefBrowser.executeJavaScript(
                """const reviewCheck = setInterval(() => {
                  const accept = document.querySelector('dialog.enhancement-review[open] .primary');
                  if (accept && !accept.disabled) { clearInterval(reviewCheck); accept.click(); }
                }, 20);
                setTimeout(() => clearInterval(reviewCheck), 10000);""",
                ReactChatPanel.PAGE,
                0,
            )
            waitUntil { received == "enhancement" }
        } finally {
            Disposer.dispose(panel)
            session.dispose()
            scope.cancel()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < end) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for the JCEF document bridge", condition())
    }
}
