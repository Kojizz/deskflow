package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

@Service
@org.springframework.context.annotation.PropertySource("classpath:projects.properties")
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
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
    void expireLeases() {
        store.db.update("update project_instances set status='STALE' where status='BUSY' and lease_until<now()");
    }
    int concurrentLimit() {
        return env.getProperty("app.projects.max-concurrent-runs",Integer.class,2);
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
            var project = store.project((UUID)instance.get("owner_id"),(UUID)instance.get("project_id"));
            if (project.get("kind").equals("git")) {
                Path marker = workspace.resolve(".git");
                if (!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)) { throw new IllegalArgumentException(); }
                String pointer = Files.readString(marker).trim();
                if (!pointer.startsWith("gitdir: ")) { throw new IllegalArgumentException(); }
                Path metadata = workspace.resolve(pointer.substring(8)).normalize().toRealPath();
                Path expected = Path.of(project.get("root_path").toString()).resolve(".git/worktrees").resolve(instance.get("id").toString()).toRealPath();
                if (!metadata.equals(expected) || !Path.of(Files.readString(metadata.resolve("gitdir")).trim()).equals(marker)) {
                    throw new IllegalArgumentException();
                }
            }
            return workspace;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Instance workspace is unavailable");
        }
    }
    String gitExecutable() {
        String configured = env.getProperty("app.projects.git-executable", "");
        if (!configured.isBlank()) {
            if (!Path.of(configured).isAbsolute()) { throw new IllegalArgumentException("Git executable must be absolute"); }
            return configured;
        }
        String name = System.getProperty("os.name").startsWith("Windows") ? "git.exe" : "git";
        for (String entry : Objects.toString(System.getenv("PATH"),"").split(java.io.File.pathSeparator)) {
            Path folder = Path.of(entry);
            Path executable = folder.resolve(name);
            if (folder.isAbsolute() && Files.isRegularFile(executable)) { return executable.toString(); }
        }
        throw new IllegalArgumentException("Configure an absolute Git executable");
    }
    List<String> gitArgs(String... args) {
        var argv = new ArrayList<String>();
        argv.add(gitExecutable());
        argv.addAll(List.of("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "protocol.file.allow=never"));
        argv.addAll(List.of(args));
        return argv;
    }
    String git(Path cwd, String... args) {
        enabled();
        var argv = gitArgs(args);
        if (Files.isRegularFile(cwd.resolve(".git"),LinkOption.NOFOLLOW_LINKS)) {
            argv.add(1,"-c");
            argv.add(2,"core.worktree="+cwd);
        }
        return ProjectProcesses.run(cwd, argv, 30, () -> false);
    }
    String validRef(String ref) {
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9_./-]{0,199}") || ref.contains("..")) {
            throw new IllegalArgumentException("Invalid Git ref");
        }
        return ref;
    }
    String ref(Path cwd, String ref) {
        validRef(ref);
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
            if (!Files.isDirectory(source.resolve(".git"),LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Register the primary Git repository root");
            }
            String top = git(source, "rev-parse", "--show-toplevel").trim();
            if (!Path.of(top).toAbsolutePath().normalize().equals(source)) {
                throw new IllegalArgumentException("Select the repository root");
            }
            String config = git(source,"config","--local","--list").toLowerCase(Locale.ROOT);
            if (config.lines().anyMatch(line -> line.startsWith("filter.") || line.startsWith("core.worktree=") || line.startsWith("include") || line.startsWith("extensions.worktreeconfig="))) {
                throw new IllegalArgumentException("Repository config contains external filters or worktree overrides");
            }
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
            int count = store.db.queryForObject("select count(*) from project_instances where owner_id=? and status<>'ARCHIVED'", Integer.class, owner);
            if (count >= quota) {
                throw new IllegalArgumentException("Instance quota exceeded");
            }
            long used = 0;
            for (var instance : store.rows("select * from project_instances where owner_id=?", owner)) {
                if (Files.exists(Path.of(instance.get("workspace_path").toString()))) {
                    used += size(path(instance));
                }
            }
            if (used + cap() > env.getProperty("app.projects.max-user-bytes", Long.class, 536870912L)) {
                throw new IllegalArgumentException("Disk quota exceeded");
            }
            Path source = root(p.get("root_path").toString());
            Map<String,Object> base = null;
            if (baseId != null) {
                base = Store.clean(store.db.queryForMap("select * from project_instances where id=? and owner_id=? for update",baseId,owner));
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
                        try (var paths = Files.list(target)) {
                        for (Path entry : paths.toList()) {
                            if (!entry.getFileName().toString().equals(".git")) { deleteTree(entry); }
                        }
                    }
                    copy(source, target, true);
                    }
                } else {
                    copy(source, target, false);
                }
                size(target);
                store.db.update("insert into project_instances(id,owner_id,project_id,name,ref,workspace_path,status,base_instance_id) values(?,?,?,?,?,?,'READY',?)", id, owner, project, Json.required(body,"name",100), revision, target.toString(), baseId);
                store.db.update("update project_instances set state_fingerprint=? where id=? and owner_id=?",fingerprint(owner,id),id,owner);
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
        try {
            var files = new ArrayList<Path>();
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                int visited;
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws java.io.IOException {
                    if (directory.getFileName().toString().equals(".git")) { return FileVisitResult.SKIP_SUBTREE; }
                    if (++visited > 10000 || !directory.toRealPath().startsWith(source.toRealPath())) {
                        throw new java.io.IOException("Unsafe tree");
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    if (file.getFileName().toString().equals(".git")) { return FileVisitResult.CONTINUE; }
                    if (++visited > 10000 || attrs.isSymbolicLink() || !attrs.isRegularFile() || !file.toRealPath().startsWith(source.toRealPath())) {
                        throw new java.io.IOException("Unsafe file");
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.naturalOrder());
            for (Path file : files) {
                action.accept(file,Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));
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
                    if (!Files.isSymbolicLink(path) && !path.toRealPath().startsWith(target.toRealPath())) {
                        throw new IllegalArgumentException("Cleanup path escapes the instance");
                    }
                    path.toFile().setWritable(true);
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
        root(p.get("root_path").toString());
        Path workspace = path(i);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            size(workspace);
            walk(workspace, (file, attrs) -> {
                digest.update(workspace.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            });
            String commit = p.get("kind").equals("git") ? ref(workspace,"HEAD") : "plain";
            if (p.get("kind").equals("git")) {
                digest.update(git(workspace,"diff","--cached","--raw","--no-abbrev","--no-ext-diff","--no-textconv").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
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
        if (result.get("allowed_commands") != null) { result.remove("allowed_commands"); }
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
                if (!p.get("kind").equals("git")) {
                    Path source = root(p.get("root_path").toString());
                    size(source);
                    deleteTree(workspace);
                    copy(source,workspace,false);
                    store.db.update("update project_instances set status='READY',state_fingerprint=? where id=? and owner_id=?",fingerprint(owner,id),id,owner);
                    return;
                }
                String revision = ref(workspace,body.path("ref").asText("HEAD"));
                git(workspace,"reset","--hard",revision);
                git(workspace,"clean","-fd");
                store.db.update("update project_instances set state_fingerprint=? where id=? and owner_id=?",fingerprint(owner,id),id,owner);
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
