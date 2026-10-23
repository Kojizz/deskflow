package dev.deskflow;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.role=test","app.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","spring.rabbitmq.listener.simple.auto-startup=false","management.health.rabbit.enabled=false","app.projects.enabled=true"})
@AutoConfigureMockMvc
class ProjectIntegrationTest {
    static Path directory;
    static {
        try {directory = Files.createTempDirectory("deskflow-project-tests-");}
        catch (Exception error) {throw new ExceptionInInitializerError(error);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
        IntegrationTest.database(props);
        props.add("app.projects.allowed-roots",directory::toString);
        props.add("app.projects.workspace-root",() -> directory.resolve("instances").toString());
    }
    @AfterAll static void cleanup() {
        IntegrationTest.stopContainer();
        ProjectManager.deleteTree(directory);
    }
    @Autowired Engine engine;
    @Autowired Store store;
    @Autowired ProjectManager manager;
    @Autowired ProjectSteps steps;
    @Autowired MockMvc mvc;
    @Autowired ImpactLab lab;
    UUID owner;
    @BeforeEach void owner() {
        owner = UUID.randomUUID();
        store.db.update("insert into users(id,email,name,password_hash) values(?,?,?,'unused')",owner,owner+"@example.invalid","Project tests");
    }
    UUID project(String kind) throws Exception {
        Path source = Files.createDirectory(directory.resolve(UUID.randomUUID().toString()));
        Files.writeString(source.resolve("file.txt"),"source");
        if (kind.equals("git")) {
            manager.git(source,"init");
            manager.git(source,"add","file.txt");
            manager.git(source,"-c","user.name=Fixture","-c","user.email=test@example.invalid","commit","-m","Initial");
        }
        var row = manager.createProject(owner,Json.M.valueToTree(Map.of("name","Fixture","rootPath",source.toString(),"kind",kind,"allowedCommands",List.of())));
        assertFalse(row.containsKey("root_path"));
        return (UUID)row.get("id");
    }
    UUID instance(UUID project) {
        return (UUID)manager.create(owner,project,Json.read("{\"name\":\"Main\",\"ref\":\"HEAD\"}"),null).get("id");
    }
    UUID run(UUID instance, int limit) {
        var definition = Json.read("{\"nodes\":["+GraphTest.node("start","manual","{}")+","+GraphTest.node("command","project.command","{\"argv\":[\"/unapproved\"],\"timeoutSeconds\":5}")+"],\"edges\":["+GraphTest.edge("start","command")+"],\"settings\":{\"effectLimit\":"+limit+"}}");
        var wf = store.save(owner,null,Json.M.valueToTree(Map.of("name","Project run","definition",definition,"active",false)));
        wf.put("_instance",instance);
        return store.enqueue(wf,Json.read("{}"),"manual",null);
    }
    @Test void plainSnapshotsAreIndependentAndOwnershipIsEnforced() throws Exception {
        UUID project = project("plain");
        UUID instance = instance(project);
        var row = store.instance(owner,instance);
        Path workspace = manager.path(row);
        Files.writeString(workspace.resolve("file.txt"),"changed");
        Path source = Path.of(store.project(owner,project).get("root_path").toString());
        assertEquals("source",Files.readString(source.resolve("file.txt")));
        assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,() -> store.instance(UUID.randomUUID(),instance));
        assertFalse(manager.view(row).containsKey("workspace_path"));
        UUID run = run(instance,1);
        assertNotNull(store.execution(owner,run).get("state_fingerprint"));
        UUID other = run(instance,1);
        assertTrue(store.claimInstance(owner,instance,run));
        assertFalse(store.claimInstance(owner,instance,other));
        assertThrows(IllegalArgumentException.class,() -> manager.maintain(owner,project,instance,"refresh",Json.read("{}")));
        store.db.update("update executions set status='SUCCEEDED' where id=? and owner_id=?",run,owner);
        store.releaseInstance(owner,instance,run,true);
        assertTrue(store.claimInstance(owner,instance,other));
    }
    @Test void gitWorktreesForkDirtyFilesAndCleanupWithoutChangingSource() throws Exception {
        UUID project = project("git");
        UUID instance = instance(project);
        Path workspace = manager.path(store.instance(owner,instance));
        Files.writeString(workspace.resolve("file.txt"),"dirty");
        UUID fork = (UUID)manager.create(owner,project,Json.read("{\"name\":\"Fork\"}"),instance).get("id");
        assertEquals("dirty",Files.readString(manager.path(store.instance(owner,fork)).resolve("file.txt")));
        Path source = Path.of(store.project(owner,project).get("root_path").toString());
        assertEquals("source",Files.readString(source.resolve("file.txt")));
        manager.maintain(owner,project,fork,"delete",Json.read("{}"));
        assertFalse(Files.exists(directory.resolve("instances").resolve(fork.toString())));
        manager.maintain(owner,project,instance,"refresh",Json.read("{}"));
        assertEquals("source",Files.readString(workspace.resolve("file.txt")));
    }
    @Test void rejectsUnapprovedCommandsAndBudgetExhaustion() throws Exception {
        UUID instance = instance(project("plain"));
        UUID run = run(instance,0);
        var command = new Graph.Node("command","project.command","Command",Json.read("{\"argv\":[\"/unapproved\"],\"timeoutSeconds\":5}"));
        assertThrows(Actions.Failure.class,() -> steps.execute(command,Json.read("{}"),owner,run));
        String executable = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var config = Json.M.valueToTree(Map.of("argv",List.of(executable,"-version"),"timeoutSeconds",5));
        UUID project = (UUID)store.instance(owner,instance).get("project_id");
        store.db.update("update projects set allowed_commands=?::jsonb where id=? and owner_id=?",Json.write(List.of(config.path("argv"))),project,owner);
        var approved = new Graph.Node("command","project.command","Command",config);
        assertThrows(Actions.Failure.class,() -> steps.execute(approved,Json.read("{}"),owner,run));
        assertEquals(0,store.db.queryForObject("select count(*) from effect_events where execution_id=?",Integer.class,run));
    }
    @Test void previewsProjectStepsWithoutRunningCommands() throws Exception {
        UUID instance = instance(project("plain"));
        UUID run = run(instance,1);
        var row = store.db.queryForMap("select definition from executions where id=? and owner_id=?",run,owner);
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode)Json.read(row.get("definition").toString());
        definition.withObject("/settings").put("instanceId",instance.toString());
        var result = lab.simulate(owner,definition,Json.read("{}"),Json.read("{}"));
        assertEquals("NEEDS_MOCK",result.get("status"));
        assertEquals(0,result.get("sideEffectsDispatched"));
        assertEquals("SUCCEEDED",lab.simulate(owner,definition,Json.read("{}"),Json.read("{\"command\":{\"output\":\"fixture\"}}")).get("status"));
    }
    @Test void apiRequiresAuthenticationAndSameOriginHeader() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/projects").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void engineRecordsEffectsAndKeepsTheLeaseUntilCompletion() throws Exception {
        UUID instance = instance(project("plain"));
        UUID run = run(instance,1);
        String executable = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var config = Json.M.valueToTree(Map.of("argv",List.of(executable,"-version"),"timeoutSeconds",5));
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode)Json.read(store.db.queryForObject("select definition::text from executions where id=? and owner_id=?",String.class,run,owner));
        ((com.fasterxml.jackson.databind.node.ObjectNode)definition.path("nodes").get(1).path("data")).set("config",config);
        store.db.update("update executions set definition=?::jsonb where id=? and owner_id=?",Json.write(definition),run,owner);
        UUID project = (UUID)store.instance(owner,instance).get("project_id");
        store.db.update("update projects set allowed_commands=?::jsonb where id=? and owner_id=?",Json.write(List.of(config.path("argv"))),project,owner);
        engine.advance(run);
        assertEquals("BUSY",store.instance(owner,instance).get("status"));
        engine.advance(run);
        engine.advance(run);
        assertEquals("SUCCEEDED",store.execution(owner,run).get("status"));
        assertEquals("READY",store.instance(owner,instance).get("status"));
        assertEquals(1,store.db.queryForObject("select count(*) from effect_events where execution_id=?",Integer.class,run));
    }
    @Test void interruptedMutationsRequireInspectionInsteadOfReplay() throws Exception {
        UUID instance = instance(project("plain"));
        UUID run = run(instance,1);
        engine.advance(run);
        store.db.update("update steps set status='RUNNING',attempts=1,retry_attempts=1 where execution_id=? and node_id='command'",run);
        engine.advance(run);
        assertEquals("FAILED",store.execution(owner,run).get("status"));
        assertTrue(store.execution(owner,run).get("error").toString().contains("inspect"));
        assertEquals(0,store.db.queryForObject("select count(*) from effect_events where execution_id=?",Integer.class,run));
    }

    @Test void tamperedGitMetadataCannotRedirectOperationsToTheOriginalTree() throws Exception {
        UUID project = project("git");
        UUID instance = instance(project);
        var row = store.instance(owner,instance);
        Path workspace = manager.path(row);
        Path source = Path.of(store.project(owner,project).get("root_path").toString());
        Files.writeString(workspace.resolve(".git"),"gitdir: "+source.resolve(".git"));
        assertThrows(IllegalArgumentException.class,() -> manager.path(row));
        assertEquals("source",Files.readString(source.resolve("file.txt")));
    }

}
