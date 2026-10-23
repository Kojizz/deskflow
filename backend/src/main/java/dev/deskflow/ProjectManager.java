package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

@Service
class ProjectManager {
    final Store store;
    final Environment env;

    ProjectManager(Store store, Environment env) {
        this.store = store;
        this.env = env;
    }
    void enabled() {
        if (!env.getProperty("app.projects.enabled", Boolean.class, false)
            || env.getProperty("app.projects.kill-switch", Boolean.class, false)
            || Files.exists(Path.of(env.getProperty("app.projects.kill-file", ".local/projects.disabled")))) {
            throw new IllegalArgumentException("Projects are disabled by the operator");
        }
    }
    long cap() {
        return env.getProperty("app.projects.max-instance-bytes", Long.class, 67108864L);
    }
    Path root(String value) {
        enabled();
        try {
            Path candidate = Path.of(value).toAbsolutePath().normalize();
            if (!candidate.toString().equals(Path.of(value).toAbsolutePath().toString())) {
                throw new IllegalArgumentException();
            }
            Path real = candidate.toRealPath();
            if (!real.equals(candidate) || !Files.isDirectory(real)) {
                throw new IllegalArgumentException();
            }
            for (String allowed : env.getProperty("app.projects.allowed-roots", "").split(",")) {
                if (!allowed.isBlank() && real.startsWith(Path.of(allowed.trim()).toRealPath())) {
                    return real;
                }
            }
        } catch (Exception ignored) {
            // Never include host paths in API errors.
        }
        throw new IllegalArgumentException("Project root is outside the operator allowlist");
    }
    static Path inside(Path base, String relative) {
        try {
            Path requested = Path.of(relative);
            if (requested.isAbsolute() || relative.indexOf('\0') >= 0) {
                throw new IllegalArgumentException();
            }
            for (Path segment : requested) {
                if (segment.toString().equals("..") || segment.toString().equals(".git")) {
                    throw new IllegalArgumentException();
                }
            }
            Path target = base.resolve(requested).toRealPath();
            if (!target.startsWith(base.toRealPath())) {
                throw new IllegalArgumentException();
            }
            return target;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Path is unavailable or escapes the instance");
        }
    }
    Path path(Map<String,Object> instance) {
        enabled();
        Path managed = Path.of(env.getProperty("app.projects.workspace-root", ".local/project-instances")).toAbsolutePath().normalize();
        Path workspace = Path.of(instance.get("workspace_path").toString());
        try {
            if (!workspace.startsWith(managed) || !workspace.toRealPath().equals(workspace)) {
                throw new IllegalArgumentException();
            }
            return workspace;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Instance workspace is unavailable");
        }
    }
    List<String> gitArgs(String... args) {
        var argv = new ArrayList<String>();
        argv.add(env.getProperty("app.projects.git-executable", "git"));
        argv.addAll(List.of("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "protocol.file.allow=never"));
        argv.addAll(List.of(args));
        return argv;
    }
    String git(Path cwd, String... args) {
        enabled();
        return ProjectProcesses.run(cwd, gitArgs(args), 30, () -> false);
    }
    String ref(Path cwd, String ref) {
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9_./-]{0,199}") || ref.contains("..")) {
            throw new IllegalArgumentException("Invalid Git ref");
        }
        return git(cwd, "rev-parse", "--verify", ref + "^{commit}").trim();
    }
    Map<String,Object> createProject(UUID owner, JsonNode body) {
        Path source = root(Json.required(body, "rootPath", 4096));
        String kind = Json.required(body, "kind", 10);
        if (!Set.of("git", "plain").contains(kind)) {
            throw new IllegalArgumentException("Unknown project kind");
        }
        JsonNode commands = body.path("allowedCommands");
        if (!commands.isArray() || commands.size() > 30) {
            throw new IllegalArgumentException("Use at most 30 exact argv allowlist entries");
        }
        for (var command : commands) {
            ProjectSteps.validateArgv(command);
        }
        if (kind.equals("git")) {
            git(source, "rev-parse", "--show-toplevel");
        }
        UUID id = UUID.randomUUID();
        store.db.update("insert into projects(id,owner_id,name,root_path,kind,allowed_commands) values(?,?,?,?,?,?::jsonb)", id, owner, Json.required(body,"name",100), source.toString(), kind, Json.write(commands));
        return view(store.project(owner, id));
    }
    Map<String,Object> create(UUID owner, UUID project, JsonNode body, UUID baseId) {
        enabled();
        return store.tx.execute(tx -> {
            store.db.queryForMap("select id from users where id=? for update", owner);
            var p = store.project(owner, project);
            if (Boolean.TRUE.equals(p.get("archived"))) {
                throw new IllegalArgumentException("Project is archived");
            }
            int quota = env.getProperty("app.projects.max-instances", Integer.class, 8);
            int count = store.db.queryForObject("select count(*) from project_instances where owner_id=?", Integer.class, owner);
            if (count >= quota) {
                throw new IllegalArgumentException("Instance quota exceeded");
            }
            long used = 0;
            for (var instance : store.rows("select * from project_instances where owner_id=?", owner)) {
                used += size(path(instance));
            }
            if (used + cap() > env.getProperty("app.projects.max-user-bytes", Long.class, 536870912L)) {
                throw new IllegalArgumentException("Disk quota exceeded");
            }
            Path source = root(p.get("root_path").toString());
            Map<String,Object> base = null;
            if (baseId != null) {
                base = store.instance(owner, baseId);
                if (!base.get("project_id").equals(project) || !base.get("status").equals("READY") || base.get("lease_token") != null) {
                    throw new IllegalArgumentException("Base instance is unavailable");
                }
                source = path(base);
            }
            UUID id = UUID.randomUUID();
            Path target = Path.of(env.getProperty("app.projects.workspace-root", ".local/project-instances")).toAbsolutePath().normalize().resolve(id.toString());
            if (target.startsWith(source) || source.startsWith(target)) {
                throw new IllegalArgumentException("Instance storage must be outside the source tree");
            }
            String revision = p.get("kind").equals("git") ? ref(source, body.path("ref").asText("HEAD")) : null;
            try {
                Files.createDirectories(target.getParent());
                if (revision != null) {
                    git(source, "worktree", "add", "--detach", target.toString(), revision);
                    if (base != null) {
                        copy(source, target, true);
                    }
                } else {
                    copy(source, target, false);
                }
                size(target);
                store.db.update("insert into project_instances(id,owner_id,project_id,name,ref,workspace_path,status,base_instance_id) values(?,?,?,?,?,?,'READY',?)", id, owner, project, Json.required(body,"name",100), revision, target.toString(), baseId);
                return view(store.instance(owner,id));
            } catch (Exception error) {
                if (revision != null) {
                    try { git(source,"worktree","remove","--force",target.toString()); } catch (Exception ignored) { }
                } else {
                    deleteTree(target);
                }
                throw new IllegalArgumentException("Instance creation failed");
            }
        });
    }
    long size(Path source) {
        long[] bytes = {0};
        walk(source, (file, attrs) -> {
            bytes[0] += attrs.size();
            if (bytes[0] > cap()) {
                throw new IllegalArgumentException("Instance size cap exceeded");
            }
        });
        return bytes[0];
    }
    interface FileAction { void accept(Path file, BasicFileAttributes attrs) throws Exception; }
    void walk(Path source, FileAction action) {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                if (source.relativize(file).toString().equals(".git") || source.relativize(file).startsWith(".git")) {
                    continue;
                }
                if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(source.toRealPath())) {
                    throw new IllegalArgumentException("Symlinks and junction escapes are forbidden");
                }
                var attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isRegularFile()) {
                    action.accept(file, attrs);
                }
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot safely inspect the instance tree");
        }
    }
    void copy(Path source, Path target, boolean overlay) {
        size(source);
        try {
            Files.createDirectories(target);
            walk(source, (file, attrs) -> {
                Path destination = target.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            });
        } catch (Exception error) {
            throw new IllegalArgumentException("Snapshot copy failed");
        }
    }
    static void deleteTree(Path target) {
        try {
            if (!Files.exists(target)) { return; }
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Instance cleanup failed");
        }
    }
    String fingerprint(UUID owner, UUID id) {
        var i = store.instance(owner,id);
        var p = store.project(owner,(UUID)i.get("project_id"));
        Path workspace = path(i);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            size(workspace);
            walk(workspace, (file, attrs) -> {
                digest.update(workspace.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            });
            String commit = p.get("kind").equals("git") ? ref(workspace,"HEAD") : "plain";
            return commit + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new IllegalArgumentException("Fingerprint unavailable");
        }
    }
    Map<String,Object> view(Map<String,Object> row) {
        var result = new LinkedHashMap<>(row);
        result.remove("root_path");
        result.remove("workspace_path");
        result.remove("lease_token");
        result.remove("lease_until");
        return result;
    }
    void maintain(UUID owner, UUID project, UUID id, String operation, JsonNode body) {
        enabled();
        store.tx.executeWithoutResult(tx -> {
            var p = store.project(owner,project);
            var i = Store.clean(store.db.queryForMap("select * from project_instances where id=? and project_id=? and owner_id=? for update",id,project,owner));
            if (i.get("lease_token") != null) {
                throw new IllegalArgumentException("Instance is leased; inspect the run before maintenance");
            }
            Path workspace = path(i);
            if (operation.equals("refresh")) {
                if (!p.get("kind").equals("git")) { throw new IllegalArgumentException("Create a fresh plain snapshot instead"); }
                String revision = ref(workspace,body.path("ref").asText("HEAD"));
                git(workspace,"reset","--hard",revision);
                git(workspace,"clean","-fd");
                store.db.update("update project_instances set ref=?,status='READY' where id=? and owner_id=?",revision,id,owner);
            } else if (operation.equals("archive")) {
                store.db.update("update project_instances set status='ARCHIVED' where id=? and owner_id=?",id,owner);
            } else if (operation.equals("delete")) {
                if (p.get("kind").equals("git")) {
                    git(root(p.get("root_path").toString()),"worktree","remove","--force",workspace.toString());
                } else { deleteTree(workspace); }
                store.db.update("update project_instances set status='ARCHIVED' where id=? and owner_id=?",id,owner);
            }
        });
    }
}
