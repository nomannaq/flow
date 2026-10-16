package dev.flow.acceptance;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestFactory;
import com.intellij.diff.merge.MergeResult;
import com.intellij.ide.plugins.DynamicPlugins;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.accessibility.AccessibleRole;
import javax.imageio.ImageIO;
import kotlinx.coroutines.flow.StateFlow;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** Installed only in an isolated acceptance profile, never in the Flow ZIP. */
public final class AcceptanceStarter implements ApplicationStarter {
    private final JsonObject result = new JsonObject();
    private String step = "startup";
    private Component panel;
    private Project project;
    private Path output;

    @Override public int getRequiredModality() { return NOT_IN_EDT; }
    @Override public boolean isHeadless() { return false; }

    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        return edt(task, ModalityState.any());
    }
    private static <T> T edt(Callable<T> task, ModalityState modality) throws Exception {
        var future = new FutureTask<T>(task);
        ApplicationManager.getApplication().invokeAndWait(future, modality);
        return future.get();
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        await(condition, 60);
    }
    private static void await(Callable<Boolean> condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.call()) {
            check(System.nanoTime() < deadline, "Acceptance condition timed out");
            Thread.sleep(50);
        }
    }
    private static List<Component> components(Component root) {
        var list = new ArrayList<Component>();
        list.add(root);
        if (root instanceof Container c) for (var child : c.getComponents()) list.addAll(components(child));
        return list;
    }
    private JButton button(String name) {
        return components(panel).stream().filter(c -> c instanceof JButton b && name.equals(b.getText()))
            .map(c -> (JButton)c).findFirst().orElseThrow();
    }
    private JList<?> list(String name) {
        return components(panel).stream().filter(c -> c instanceof JList<?> && c.getAccessibleContext() != null && name.equals(c.getAccessibleContext().getAccessibleName()))
            .map(c -> (JList<?>)c).findFirst().orElseThrow();
    }
    private JTextComponent field(String name) {
        return components(panel).stream().filter(c -> c instanceof JTextComponent && c.getAccessibleContext() != null
            && name.equals(c.getAccessibleContext().getAccessibleName())).map(c -> (JTextComponent)c).findFirst().orElseThrow();
    }
    private void click(String name) {
        var b = button(name);
        check(b.isEnabled(), "Acceptance button disabled: " + name);
        b.doClick(0);
    }
    private Object view() throws Exception {
        var descriptor = PluginManagerCore.getPlugin(PluginId.getId("dev.flow.workbench"));
        var serviceClass = descriptor.getPluginClassLoader().loadClass("dev.flow.bridge.EngineService");
        Object service = project.getService(serviceClass);
        var getter = java.util.Arrays.stream(serviceClass.getMethods()).filter(m -> m.getName().startsWith("getController")).findFirst().orElseThrow();
        Object controller = getter.invoke(service);
        Object flow = controller.getClass().getMethod("getView").invoke(controller);
        return ((StateFlow<?>)flow).getValue();
    }
    private Object agentField(String field) throws Exception {
        Object v = view();
        Object agent = v.getClass().getMethod("getAgent").invoke(v);
        return agent.getClass().getMethod(field).invoke(agent);
    }
    private ProcessHandle engine() throws Exception {
        Object v = view();
        Long pid = (Long)v.getClass().getMethod("getPid").invoke(v);
        return pid == null ? null : ProcessHandle.of(pid).orElse(null);
    }
    private Window displayedPair(String before, String after) {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (!editor.isViewer() || !after.equals(editor.getDocument().getText()) || !editor.getComponent().isShowing()) continue;
            Window window = javax.swing.SwingUtilities.getWindowAncestor(editor.getComponent());
            if (window != null && window.isShowing() && java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .anyMatch(e -> e.isViewer() && before.equals(e.getDocument().getText()) && e.getComponent().isShowing()
                    && javax.swing.SwingUtilities.getWindowAncestor(e.getComponent()) == window)) return window;
        }
        return null;
    }
    private void verifyEnginePayload(ProcessHandle process) throws Exception {
        Path expected = Path.of(System.getProperty("flow.acceptance.engine")).toRealPath();
        Path command = Path.of(process.info().command().orElseThrow());
        String identity = "native executable path";
        if (System.getProperty("os.name").equals("Linux") && command.equals(Path.of("/run/rosetta/rosetta"))) {
            // Docker Desktop's emulator is /proc/exe; argv[0] retains the actual payload.
            Path proc = Path.of("/proc", Long.toString(process.pid()));
            check(Files.readSymbolicLink(proc.resolve("exe")).equals(command), "Unexpected emulator executable");
            String argv = Files.readString(proc.resolve("cmdline"));
            int end = argv.indexOf('\0');
            check(end > 0, "Missing emulated payload command");
            command = Path.of(argv.substring(0, end));
            identity = "Linux Rosetta /proc command line";
        }
        check(command.toRealPath().equals(expected), "IDE launched a different engine payload");
        result.addProperty("engineExecutableIdentity", identity);
    }
    private void mount() throws Exception {
        project = ProjectUtil.openOrImport(Path.of(System.getProperty("flow.acceptance.project")));
        check(project != null, "Owned acceptance project could not open");
        await(() -> edt(() -> ToolWindowManager.getInstance(project).getToolWindow("Flow") != null));
        edt(() -> {
            ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Flow");
            window.activate(null);
            panel = window.getComponent();
            return null;
        });
        await(() -> edt(() -> engine() != null && engine().isAlive()));
        verifyEnginePayload(edt(this::engine));
        check(edt(() -> agentField("getId")) == null, "History reopen auto-started an agent");
        result.addProperty("noAgentAutoResume", true);
        String version = edt(() -> PluginManagerCore.getPlugin(PluginId.getId("dev.flow.workbench")).getVersion());
        check(version.equals(System.getProperty("flow.acceptance.pluginVersion")), "Installed plugin version differs from selected archive");
        result.addProperty("pluginVersion", version);
    }
    private void historicalDiffs() throws Exception {
        step = "rendered historical diffs";
        edt(() -> {
            components(panel).stream().filter(c -> c instanceof JTabbedPane).map(c -> (JTabbedPane)c)
                .filter(t -> t.indexOfTab("History") >= 0).findFirst().orElseThrow().setSelectedIndex(1);
            return null;
        });
        await(() -> edt(() -> button("Refresh sessions").isEnabled()));
        edt(() -> { click("Refresh sessions"); return null; });
        await(() -> edt(() -> list("Sessions").getModel().getSize() > 0));
        edt(() -> {
            var rows = list("Sessions");
            String id = System.getProperty("flow.acceptance.sessionId");
            int index = -1;
            for (int i = 0; i < rows.getModel().getSize(); i++) if (rows.getModel().getElementAt(i).toString().contains(id)) index = i;
            check(index >= 0, "Seeded history session was not found");
            rows.setSelectedIndex(index);
            return null;
        });
        await(() -> edt(() -> button("All operations").isEnabled()));
        edt(() -> { click("All operations"); return null; });
        await(() -> edt(() -> list("Recorded operations").getModel().getSize() >= 2));
        int matched = 0;
        int count = edt(() -> list("Recorded operations").getModel().getSize());
        for (int operation = 0; operation < count && matched < 2; operation++) {
            final int row = operation;
            edt(() -> { list("Recorded operations").setSelectedIndex(row); return null; });
            await(() -> edt(() -> ((DefaultMutableTreeNode)historyTree().getModel().getRoot()).getChildCount() > 0));
            boolean found = edt(() -> {
                var tree = historyTree();
                var nodes = ((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();
                while (nodes.hasMoreElements()) {
                    var node = (DefaultMutableTreeNode)nodes.nextElement();
                    if (node.isLeaf() && node.toString().contains(System.getProperty("flow.acceptance.filename"))) {
                        tree.setSelectionPath(new TreePath(node.getPath()));
                        return button("Open historical diff").isEnabled();
                    }
                }
                return false;
            });
            if (!found) continue;
            final int index = matched++;
            edt(() -> { click("Open historical diff"); return null; });
            String expected = System.getProperty("flow.acceptance.version" + (index + 1));
            String before = index == 0 ? "" : System.getProperty("flow.acceptance.version1");
            await(() -> edt(() -> displayedPair(before, expected) != null));
            edt(() -> {
                var window = displayedPair(before, expected);
                check(window != null, "Historical diff is not displayed with both exact read-only inputs");
                var image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { window.paint(graphics); } finally { graphics.dispose(); }
                ImageIO.write(image, "png", output.resolveSibling(System.getProperty("flow.acceptance.phase") + "-diff-" + index + ".png").toFile());
                result.addProperty("renderedReadOnlyDiff" + index, true);
                return null;
            });
        }
        check(matched == 2, "Both immutable historical transitions were not displayed");
    }
    private JTree historyTree() {
        return components(panel).stream().filter(c -> c instanceof JTree && c.getAccessibleContext() != null && "Files in the selected operation".equals(c.getAccessibleContext().getAccessibleName()))
            .map(c -> (JTree)c).findFirst().orElseThrow();
    }
    private void compactControls() throws Exception {
        step = "compact review and apply controls";
        for (String title : List.of("Review result", "Apply result")) {
            edt(() -> {
                var tabs = components(panel).stream().filter(c -> c instanceof JTabbedPane)
                    .map(c -> (JTabbedPane)c).filter(t -> t.indexOfTab(title) >= 0).findFirst().orElseThrow();
                tabs.setSelectedIndex(tabs.indexOfTab(title));
                panel.validate();
                return null;
            });
            edt(() -> {
                var tabs = components(panel).stream().filter(c -> c instanceof JTabbedPane)
                    .map(c -> (JTabbedPane)c).filter(t -> t.indexOfTab(title) >= 0).findFirst().orElseThrow();
                for (var c : components(tabs.getSelectedComponent())) if (c instanceof JButton b && b.isShowing()) {
                    check(b.getHeight() > 0 && b.getHeight() <= 48, "Action button has inflated height: " + b.getText());
                }
                var files = list("Apply result".equals(title) ? "Application files" : "Complete-result files");
                check(files.getParent().getHeight() >= 32, "File viewport is hidden by the action controls");
                var image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { panel.paint(graphics); } finally { graphics.dispose(); }
                ImageIO.write(image, "png", output.resolveSibling(System.getProperty("flow.acceptance.phase") + "-" + title.replace(' ', '-') + ".png").toFile());
                return null;
            });
        }
        result.addProperty("compactReviewAndApplyControls", true);
    }
    private void startFixture() throws Exception {
        step = "live offline adapter";
        edt(() -> {
            components(panel).stream().filter(c -> c instanceof JTabbedPane).map(c -> (JTabbedPane)c)
                .filter(t -> t.indexOfTab("Session") >= 0).findFirst().orElseThrow().setSelectedIndex(0);
            for (var c : components(panel)) if (c instanceof JTextComponent text && c.getAccessibleContext() != null) {
                String name = c.getAccessibleContext().getAccessibleName();
                if ("ACP adapter executable".equals(name)) text.setText(System.getProperty("flow.acceptance.python"));
                if ("Advanced adapter arguments".equals(name)) text.setText(new Gson().toJson(new String[]{System.getProperty("flow.acceptance.fixture"), "--heartbeat"}));
                if ("Model override".equals(name)) text.setText("");
                if ("Task prompt".equals(name)) text.setText("command waits 30 seconds");
            }
            click("Use current commit");
            return null;
        });
        await(() -> edt(() -> button("Start Codex session").isEnabled() && field("Clean baseline commit").getText().matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")));
        edt(() -> { click("Start Codex session"); return null; });
        await(() -> edt(() -> "running".equals(agentField("getStatus")) && engine().descendants().count() >= 2));
    }
    private void realCodex() throws Exception {
        step = "checksum-pinned real Codex native submission";
        Path adapter = Path.of(System.getProperty("flow.acceptance.adapter"));
        String expectedHash = System.getProperty("flow.acceptance.adapterSha256");
        String actualHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(adapter)));
        check(actualHash.equals(expectedHash), "Pinned real adapter bytes changed");
        edt(() -> {
            var tabs = components(panel).stream().filter(c -> c instanceof JTabbedPane).map(c -> (JTabbedPane)c)
                .filter(t -> t.indexOfTab("Session") >= 0).findFirst().orElseThrow();
            tabs.setSelectedIndex(0);
            field("ACP adapter executable").setText(adapter.toString());
            field("Advanced adapter arguments").setText("[]");
            field("Model override").setText(System.getProperty("flow.acceptance.model"));
            field("Task prompt").setText("Create flow-b29.txt containing exactly \"version 1\" followed by a newline. Do not edit other files.");
            click("Use current commit");
            return null;
        });
        await(() -> edt(() -> button("Start Codex session").isEnabled() && field("Clean baseline commit").getText().matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")));
        edt(() -> { click("Start Codex session"); return null; });
        var answered = new java.util.HashSet<String>();
        for (int turn = 1; turn <= 2; turn++) {
            final String expected = "version " + turn + "\n";
            await(() -> edt(() -> {
                String status = (String)agentField("getStatus");
                check(!List.of("failed", "authenticationRequired", "closed", "interrupted").contains(status), "Real adapter could not complete; check native login/model");
                for (Object permission : (java.util.Collection<?>)agentField("getPermissions")) {
                    String id = (String)permission.getClass().getMethod("getId").invoke(permission);
                    if (answered.contains(id)) continue;
                    var once = (java.util.Set<?>)permission.getClass().getMethod("getAllowOnce").invoke(permission);
                    check(!once.isEmpty(), "Real adapter did not advertise a one-time approval");
                    var option = components(panel).stream().filter(c -> c instanceof JButton b && b.isEnabled()
                        && id.equals(b.getClientProperty("flow.permission.requestId"))
                        && once.contains(b.getClientProperty("flow.permission.optionId"))).map(c -> (JButton)c).findFirst();
                    if (option.isPresent()) { answered.add(id); option.get().doClick(0); }
                }
                String workspace = (String)agentField("getWorkspace");
                if (workspace == null || !"ready".equals(status)) return false;
                Path root = Path.of(workspace).toRealPath();
                check(root.startsWith(Path.of(System.getProperty("flow.engine.dataRoot")).toRealPath()), "Real fixture workspace escaped owned storage");
                Path file = root.resolve("flow-b29.txt");
                return Files.isRegularFile(file) && expected.equals(Files.readString(file));
            }), 180);
            if (turn == 1) edt(() -> {
                field("Task prompt").setText("Change flow-b29.txt to contain exactly \"version 2\" followed by a newline. Do not edit other files.");
                click("Send follow-up");
                return null;
            });
        }
        String session = (String)edt(() -> agentField("getId"));
        Path workspace = Path.of((String)edt(() -> agentField("getWorkspace"))).toRealPath();
        edt(() -> { click("Close session"); return null; });
        await(() -> edt(() -> "closed".equals(agentField("getStatus"))), 60);
        result.addProperty("realWriterShutdownVerified", true);
        result.addProperty("realSessionId", session);
        result.addProperty("realCodexTwoEdits", true);
        System.setProperty("flow.acceptance.sessionId", session);
        System.setProperty("flow.acceptance.filename", "flow-b29.txt");
        System.setProperty("flow.acceptance.version1", "version 1\n");
        System.setProperty("flow.acceptance.version2", "version 2\n");
        historicalDiffs();
        Files.delete(workspace.resolve("flow-b29.txt"));
        historicalDiffs();
        result.addProperty("realHistoricalDiffsAfterLiveDeletion", true);
    }
    private void renderedMerges() throws Exception {
        step = "rendered native merge Apply and Cancel";
        for (boolean accept : List.of(false, true)) {
            var outcome = new AtomicReference<MergeResult>();
            var outputDocument = edt(() -> EditorFactory.getInstance().createDocument("setting=base\n"));
            edt(() -> {
                var request = DiffRequestFactory.getInstance().createMergeRequest(project, PlainTextFileType.INSTANCE,
                    outputDocument, List.of("setting=developer\n", "setting=base\n", "setting=agent\n"),
                    "Flow acceptance merge", List.of("Current project", "Session baseline", "Agent candidate"), outcome::set);
                ApplicationManager.getApplication().invokeLater(() -> DiffManager.getInstance().showMerge(project, request), ModalityState.any());
                return null;
            });
            await(() -> edt(() -> java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .anyMatch(e -> e.getDocument() == outputDocument && !e.isViewer() && e.getComponent().isShowing())));
            Window window = edt(() -> {
                Editor editor = java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                    .filter(e -> e.getDocument() == outputDocument && e.getComponent().isShowing()).findFirst().orElseThrow();
                var parent = javax.swing.SwingUtilities.getWindowAncestor(editor.getComponent());
                check(parent != null && parent.isShowing(), "Native merge window is not visible");
                for (String text : List.of("setting=developer\n", "setting=agent\n")) {
                    check(java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors()).anyMatch(e -> e.isViewer()
                        && text.equals(e.getDocument().getText()) && e.getComponent().isShowing()
                        && javax.swing.SwingUtilities.getWindowAncestor(e.getComponent()) == parent), "Native merge input was not rendered read-only");
                }
                WriteCommandAction.runWriteCommandAction(project, () -> outputDocument.setText("setting=resolved\n"));
                var image = new BufferedImage(parent.getWidth(), parent.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { parent.paint(graphics); } finally { graphics.dispose(); }
                ImageIO.write(image, "png", output.resolveSibling(System.getProperty("flow.acceptance.phase") + "-merge-" + (accept ? "apply" : "cancel") + ".png").toFile());
                return parent;
            });
            edt(() -> {
                var action = components(window).stream().filter(c -> c instanceof JButton b && b.isShowing()
                    && (accept ? "Apply" : "Cancel").equals(b.getText())).map(c -> (JButton)c).findFirst().orElseThrow();
                check(action.isEnabled(), "Native merge action is disabled");
                ApplicationManager.getApplication().invokeLater(() ->
                    check(action.getAccessibleContext().getAccessibleAction().doAccessibleAction(0), "Native merge accessible action failed"), ModalityState.any());
                return null;
            });
            var confirmed = new java.util.concurrent.atomic.AtomicBoolean();
            await(() -> edt(() -> {
                for (Window dialog : window.getOwnedWindows()) if (dialog.isShowing()) {
                    String label = accept ? "Apply Changes and Mark Resolved" : "Discard Changes and Cancel Merge";
                    var confirmation = components(dialog).stream().filter(c -> c instanceof JButton b && b.isShowing()
                        && label.equals(b.getText())).map(c -> (JButton)c).findFirst();
                    if (confirmation.isPresent()) {
                        check(confirmation.get().isEnabled(), "Native merge confirmation is disabled");
                        confirmed.set(true);
                        confirmation.get().doClick(0);
                    }
                }
                return outcome.get() != null;
            }));
            check(confirmed.get(), "Edited native merge was not confirmed through its owned dialog");
            check(outcome.get() == (accept ? MergeResult.RESOLVED : MergeResult.CANCEL), "Native merge returned " + outcome.get() + " for " + (accept ? "Apply" : "Cancel"));
            check(edt(outputDocument::getText).equals(accept ? "setting=resolved\n" : "setting=base\n"), "Native merge returned unexpected output bytes");
        }
        result.addProperty("renderedMergeApplyAndCancel", true);
    }
    private void accessibleControls() throws Exception {
        step = "accessible controls and keyboard bindings";
        edt(() -> {
            var tabs = components(panel).stream().filter(c -> c instanceof JTabbedPane).map(c -> (JTabbedPane)c)
                .filter(t -> t.indexOfTab("Session") >= 0).findFirst().orElseThrow();
            tabs.setSelectedIndex(0);
            check(tabs.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).allKeys() != null, "Tabs have no keyboard bindings");
            for (String name : List.of("ACP adapter executable", "Advanced adapter arguments", "Model override", "Task prompt")) {
                var field = components(panel).stream().filter(c -> c instanceof JTextComponent && c.getAccessibleContext() != null
                    && name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                check(field.isFocusable(), "Input is not keyboard focusable: " + name);
                check(field.getAccessibleContext().getAccessibleRole() != AccessibleRole.UNKNOWN, "Input has no accessible role");
            }
            for (var c : components(panel)) if (c instanceof JButton b && b.getText() != null && !b.getText().isBlank()) {
                check(b.getAccessibleContext().getAccessibleName() != null && !b.getAccessibleContext().getAccessibleName().isBlank(), "Action has no accessible name");
                check(b.getAccessibleContext().getAccessibleAction() != null, "Action is unavailable to assistive technology");
            }
            // Invoke the platform tab action through its keyboard binding.
            var key = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0);
            Object binding = tabs.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(key);
            check(binding != null && tabs.getActionMap().get(binding) != null, "Right-arrow tab action is unavailable");
            tabs.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(tabs, 0, binding.toString()));
            check(tabs.getSelectedIndex() == 1, "Right-arrow did not navigate to History");
            return null;
        });
        result.addProperty("accessibleNamesRolesAndActions", true);
        result.addProperty("keyboardTabNavigation", true);
        // A native screen-reader observation remains a separate human check.
    }
    @Override public void main(List<String> args) {
        output = Path.of(System.getProperty("flow.acceptance.output"));
        result.addProperty("passed", false);
        result.addProperty("idePid", ProcessHandle.current().pid());
        result.addProperty("phase", System.getProperty("flow.acceptance.phase"));
        try {
            check(System.getProperty("flow.engine.path") == null, "Desktop acceptance cannot use a development engine override");
            check(!ApplicationManager.getApplication().isUnitTestMode(), "A full production IDE process is required");
            mount();
            if ("real".equals(System.getProperty("flow.acceptance.phase"))) realCodex();
            else historicalDiffs();
            compactControls();
            renderedMerges();
            accessibleControls();
            startFixture();
            var owned = edt(() -> engine());
            var processes = new ArrayList<ProcessHandle>();
            processes.add(owned);
            processes.addAll(owned.descendants().toList());
            check(owned.parent().orElseThrow().pid() == ProcessHandle.current().pid(), "Engine is not owned by this IDE");
            result.add("ownedPids", new Gson().toJsonTree(processes.stream().map(ProcessHandle::pid).toList()));
            panel = null; // Do not retain a plugin UI/classloader across unload.
            if ("unload".equals(System.getProperty("flow.acceptance.phase"))) {
                step = "dynamic plugin unload";
                boolean unloaded = edt(() -> DynamicPlugins.INSTANCE.unloadPlugin((IdeaPluginDescriptorImpl)PluginManagerCore.getPlugin(PluginId.getId("dev.flow.workbench"))), ModalityState.nonModal());
                check(unloaded, "Flow could not unload without restarting the IDE");
                result.addProperty("pluginUnloaded", true);
            } else {
                step = "project close";
                check(edt(() -> ProjectUtil.closeAndDispose(project), ModalityState.nonModal()), "Owned project did not close");
                result.addProperty("projectDisposed", project.isDisposed());
            }
            await(() -> processes.stream().noneMatch(ProcessHandle::isAlive));
            result.addProperty("engineAndDescendantsStopped", true);
            result.addProperty("passed", true);
        } catch (Throwable failure) {
            result.addProperty("failedStep", step);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            result.addProperty("failureType", cause.getClass().getName());
            result.addProperty("failureMessage", cause.getMessage());
            result.add("failureStack", new Gson().toJsonTree(java.util.Arrays.stream(cause.getStackTrace()).limit(8).map(Object::toString).toList()));
        } finally {
            try { Files.writeString(output, new Gson().toJson(result)); } catch (Exception ignored) { }
            ApplicationManagerEx.getApplicationEx().exit(true, true, 0);
        }
    }
}
