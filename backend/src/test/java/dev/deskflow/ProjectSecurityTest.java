package dev.deskflow;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ProjectSecurityTest {
    @TempDir Path directory;
    List<String> fixture(String... arguments) {
        var argv = new ArrayList<String>();
        argv.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
        argv.addAll(List.of("-cp",Path.of("target/test-classes").toAbsolutePath().toString(),ProjectProcessFixture.class.getName()));
        argv.addAll(List.of(arguments));
        return argv;
    }
    @Test void rejectsTraversalAndSymlinkEscapes() throws Exception {
        Path base = Files.createDirectory(directory.resolve("instance"));
        Path secret = Files.writeString(directory.resolve("secret"),"outside");
        assertThrows(IllegalArgumentException.class,() -> ProjectManager.inside(base,"../secret"));
        assertThrows(IllegalArgumentException.class,() -> ProjectManager.inside(base,secret.toString()));
        try {Files.createSymbolicLink(base.resolve("escape"),secret);}
        catch (Exception unavailable) {Assumptions.abort("Symlink creation requires local OS permission");}
        assertThrows(IllegalArgumentException.class,() -> ProjectManager.inside(base,"escape"));
        assertThrows(IllegalArgumentException.class,() -> ProjectManager.inside(base,".git/config"));
    }
    @Test void operatorRootsAndKillSwitchAreEnforced() throws Exception {
        Path allowed = Files.createDirectory(directory.resolve("allowed"));
        var env = new MockEnvironment().withProperty("app.projects.enabled","true").withProperty("app.projects.allowed-roots",allowed.toString());
        var manager = new ProjectManager(null,env);
        assertEquals(allowed.toRealPath(),manager.root(allowed.toString()));
        assertThrows(IllegalArgumentException.class,() -> manager.root(directory.toString()));
        env.setProperty("app.projects.kill-switch","true");
        assertThrows(IllegalArgumentException.class,manager::enabled);
        assertThrows(IllegalArgumentException.class,new ProjectManager(null,new MockEnvironment())::enabled);
    }
    @Test void templatesStayOneLiteralArgumentWithoutAShell() {
        var configured = Json.read("[\"executable\",\"{{input.value}}\"]");
        String injection = "; echo hacked & $(touch injected)";
        var args = ProjectSteps.argv(configured,Json.M.valueToTree(Map.of("input",Map.of("value",injection))));
        assertEquals(List.of("executable",injection),args);
        assertEquals(injection,ProjectProcesses.run(directory,fixture("echo",args.get(1)),5,() -> false));
        assertFalse(Files.exists(directory.resolve("injected")));
    }
    @Test void environmentIsScrubbedAndOutputIsBounded() {
        assertEquals("true",ProjectProcesses.run(directory,fixture("environment"),5,() -> false));
        assertThrows(Actions.Failure.class,() -> ProjectProcesses.run(directory,fixture("large"),5,() -> false));
    }
    @Test void cancellationKillsDescendants() throws Exception {
        var cancelled = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> ProjectProcesses.run(directory,fixture("parent"),10,cancelled::get));
            Path pid = directory.resolve("child.pid");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(pid) || Files.size(pid) == 0) && System.nanoTime() < deadline) {Thread.sleep(25);}
            assertTrue(Files.exists(pid));
            long child = Long.parseLong(Files.readString(pid));
            cancelled.set(true);
            assertThrows(ExecutionException.class,() -> task.get(5,TimeUnit.SECONDS));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        }
    }
    @Test void timeoutTerminatesTheCommand() {
        long start = System.nanoTime();
        assertThrows(Actions.Failure.class,() -> ProjectProcesses.run(directory,fixture("child"),1,() -> false));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start) < 5);
    }
}
