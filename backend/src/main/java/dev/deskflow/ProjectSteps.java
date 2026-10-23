package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

@Service
class ProjectSteps {
    final ProjectManager projects;
    final Store store;
    final EffectGuard guard;
    ProjectSteps(ProjectManager projects, Store store, EffectGuard guard) {
        this.projects = projects;
        this.store = store;
        this.guard = guard;
    }
    static void validateArgv(JsonNode argv) {
        if (!argv.isArray() || argv.isEmpty() || argv.size() > 64) {
            throw new IllegalArgumentException("Use an argv array with 1–64 entries");
        }
        for (var arg : argv) {
            if (!arg.isTextual() || arg.asText().length() > 4096 || arg.asText().indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Arguments must be bounded strings");
            }
        }
    }
    static void validate(String kind, JsonNode config) {
        if (kind.equals("project.command")) {
            validateArgv(config.path("argv"));
            Graph.range(config, "timeoutSeconds", 1, 60);
        } else if (kind.equals("project.read")) {
            Json.required(config,"path",1024);
        } else if (!Set.of("status","diff","log","checkout","commit").contains(config.path("operation").asText())) {
            throw new IllegalArgumentException("Unsupported project Git operation");
        }
    }
    static List<String> argv(JsonNode configured, JsonNode context) {
        var result = new ArrayList<String>();
        for (var arg : configured) {
            JsonNode rendered = Templates.render(arg,context);
            if (!rendered.isTextual() || rendered.asText().indexOf('\0') >= 0 || rendered.asText().length() > 4096) {
                throw new IllegalArgumentException("Each template must produce one string argument");
            }
            result.add(rendered.asText());
        }
        return result;
    }
    JsonNode execute(Graph.Node node, JsonNode context, UUID owner, UUID run) {
        projects.enabled();
        var execution = store.db.queryForMap("select instance_id,project_id,root_id from executions where id=? and owner_id=?",run,owner);
        if (!(execution.get("instance_id") instanceof UUID instanceId)) {
            throw new Actions.Failure("Select a project instance before running",false);
        }
        var instance = store.instance(owner,instanceId);
        var project = store.project(owner,(UUID)instance.get("project_id"));
        Path workspace = projects.path(instance);
        var config = Templates.render(node.config(),context);
        if (node.kind().equals("project.read")) {
            return read(workspace,config);
        }
        List<String> argv;
        if (node.kind().equals("project.command")) {
            argv = argv(node.config().path("argv"),context);
            boolean allowed = false;
            for (var entry : (JsonNode)project.get("allowed_commands")) {
                if (Json.M.valueToTree(argv).equals(entry)) { allowed = true; }
            }
            if (!allowed) { throw new Actions.Failure("Command argv is outside the project allowlist",false); }
            String exe = Path.of(argv.getFirst()).getFileName().toString().toLowerCase(Locale.ROOT).replace(".exe", "");
            if (Set.of("sh","bash","zsh","cmd","powershell","pwsh","fish","wscript","cscript").contains(exe) || !Path.of(argv.getFirst()).isAbsolute()) {
                throw new Actions.Failure("Use an absolute executable path without a shell",false);
            }
        } else {
            if (!project.get("kind").equals("git")) { throw new IllegalArgumentException("Git steps require a Git instance"); }
            argv = git(workspace,config);
        }
        if (store.db.queryForObject("select count(*) from effect_events where execution_id=? and node_id=?",Integer.class,run,node.id()) > 0) {
            throw new Actions.Failure("Inspect before resume: this project command may already have run",false);
        }
        guard.reserve(run,node,"instance:"+instanceId);
        UUID root = execution.get("root_id") == null ? run : (UUID)execution.get("root_id");
        String output = ProjectProcesses.run(workspace,argv,config.path("timeoutSeconds").asInt(30),() -> {
            projects.enabled();
            return Boolean.TRUE.equals(store.db.queryForObject("select exists(select 1 from executions where owner_id=? and id in (?,?) and cancel_requested)",Boolean.class,owner,run,root));
        });
        projects.size(workspace);
        return Json.M.valueToTree(Json.map("output",store.redact(owner,output),"instanceId",instanceId,"fingerprint",projects.fingerprint(owner,instanceId)));
    }
    JsonNode read(Path workspace, JsonNode config) {
        Path target = ProjectManager.inside(workspace,config.path("path").asText());
        try {
            if (Files.isDirectory(target)) {
                try (var entries = Files.list(target)) {
                    var names = entries.limit(501).map(p -> p.getFileName().toString()).filter(n -> !n.equals(".git")).sorted().toList();
                    if (names.size() > 500) { throw new IllegalArgumentException("Directory listing exceeds 500 entries"); }
                    return Json.M.valueToTree(Map.of("entries",names));
                }
            }
            try (var input = Files.newInputStream(target)) {
                byte[] bytes = input.readNBytes(65537);
                if (bytes.length > 65536) { throw new IllegalArgumentException("Read exceeds 64 KB"); }
                return Json.M.valueToTree(Map.of("text",new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("File is unavailable");
        }
    }
    List<String> git(Path workspace, JsonNode config) {
        return switch (config.path("operation").asText()) {
            case "status" -> projects.gitArgs("status","--porcelain");
            case "diff" -> projects.gitArgs("diff","--no-ext-diff","--no-textconv","--");
            case "log" -> projects.gitArgs("log","-20","--format=%h %s");
            case "checkout" -> projects.gitArgs("checkout","--detach",projects.ref(workspace,config.path("ref").asText("HEAD")));
            case "commit" -> projects.gitArgs("-c","user.name=DeskFlow","-c","user.email=automation@deskflow.local","-c","commit.gpgsign=false","commit","-am",Json.required(config,"message",200));
            default -> throw new IllegalArgumentException("Unsupported Git operation");
        };
    }
}
