package dev.flow.acceptance;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.plugins.DynamicPlugins;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
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
        var future = new FutureTask<T>(task);
        ApplicationManager.getApplication().invokeAndWait(future);
        return future.get();
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
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
        check(edt(() -> Path.of(engine().info().command().orElseThrow()).toRealPath()).equals(Path.of(System.getProperty("flow.acceptance.engine")).toRealPath()), "IDE launched a different engine payload");
        check(edt(() -> agentField("getId")) == null, "History reopen auto-started an agent");
        result.addProperty("noAgentAutoResume", true);
    }
    private void historicalDiffs() throws Exception {
        step = "rendered historical diffs";
        edt(() -> {
            components(panel).stream().filter(c -> c instanceof JTabbedPane).map(c -> (JTabbedPane)c)
                .filter(t -> t.indexOfTab("History") >= 0).findFirst().orElseThrow().setSelectedIndex(1);
            click("Refresh sessions");
            return null;
        });
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
            await(() -> edt(() -> java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .anyMatch(e -> e.isViewer() && expected.equals(e.getDocument().getText()) && e.getComponent().isShowing())));
            edt(() -> {
                Editor editor = java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                    .filter(e -> e.isViewer() && expected.equals(e.getDocument().getText()) && e.getComponent().isShowing()).findFirst().orElseThrow();
                var window = javax.swing.SwingUtilities.getWindowAncestor(editor.getComponent());
                check(window != null && window.isShowing(), "Historical diff is not displayed in a native window");
                var image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { window.paint(graphics); } finally { graphics.dispose(); }
                ImageIO.write(image, "png", output.resolveSibling(System.getProperty("flow.acceptance.phase") + "-diff-" + index + ".png").toFile());
                String before = index == 0 ? "" : System.getProperty("flow.acceptance.version1");
                check(java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors()).anyMatch(e -> e.isViewer() && before.equals(e.getDocument().getText()) && e.getComponent().isShowing() && javax.swing.SwingUtilities.getWindowAncestor(e.getComponent()) == window), "Historical before bytes differ in the rendered diff");
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
        await(() -> edt(() -> button("Start Codex session").isEnabled()));
        edt(() -> { click("Start Codex session"); return null; });
        await(() -> edt(() -> "running".equals(agentField("getStatus")) && engine().descendants().count() >= 2));
    }
    @Override public void main(List<String> args) {
        output = Path.of(System.getProperty("flow.acceptance.output"));
        result.addProperty("passed", false);
        result.addProperty("idePid", ProcessHandle.current().pid());
        try {
            check(System.getProperty("flow.engine.path") == null, "Desktop acceptance cannot use a development engine override");
            check(!ApplicationManager.getApplication().isUnitTestMode(), "A full production IDE process is required");
            mount();
            historicalDiffs();
            compactControls();
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
                boolean unloaded = edt(() -> DynamicPlugins.INSTANCE.unloadPlugin((IdeaPluginDescriptorImpl)PluginManagerCore.getPlugin(PluginId.getId("dev.flow.workbench"))));
                check(unloaded, "Flow could not unload without restarting the IDE");
                result.addProperty("pluginUnloaded", true);
            } else {
                step = "project close";
                check(edt(() -> ProjectUtil.closeAndDispose(project)), "Owned project did not close");
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
