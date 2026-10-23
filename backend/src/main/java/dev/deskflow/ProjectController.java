package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects")
class ProjectController {
    final ProjectManager projects;
    final Store store;
    final ImpactLab lab;
    ProjectController(ProjectManager projects, Store store, ImpactLab lab) {
        this.projects = projects;
        this.store = store;
        this.lab = lab;
    }
    UUID owner(HttpServletRequest request) {
        return (UUID)request.getAttribute("userId");
    }
    @GetMapping("/status") Object status() {
        try {
            projects.enabled();
            return Map.of("enabled",true);
        } catch (IllegalArgumentException ignored) {
            return Map.of("enabled",false);
        }
    }
    @GetMapping Object list(HttpServletRequest request) {
        projects.enabled();
        return store.rows("select id,name,kind,created_at,archived from projects where owner_id=? and deleted_at is null order by created_at desc limit 100",owner(request));
    }
    @GetMapping("/{id}") Object get(@PathVariable UUID id, HttpServletRequest request) {
        projects.enabled();
        return projects.view(store.project(owner(request),id));
    }
    @PostMapping Object create(@RequestBody JsonNode body, HttpServletRequest request) {
        return projects.createProject(owner(request),body);
    }
    @PutMapping("/{id}") Object update(@PathVariable UUID id, @RequestBody JsonNode body, HttpServletRequest request) {
        projects.enabled();
        UUID owner = owner(request);
        return store.tx.execute(tx -> {
            store.db.queryForMap("select id from users where id=? for update", owner);
            store.project(owner,id);
            if (body.path("archived").asBoolean() && store.db.queryForObject(
                "select count(*) from project_instances where project_id=? and owner_id=? and deleted_at is null and lease_token is not null",
                Integer.class, id, owner) > 0) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Wait for the project's active executions before archiving");
            }
            store.db.update("update projects set name=?,archived=? where id=? and owner_id=?",Json.required(body,"name",100),body.path("archived").asBoolean(),id,owner);
            return projects.view(store.project(owner,id));
        });
    }
    @DeleteMapping("/{id}") Object delete(@PathVariable UUID id, HttpServletRequest request) {
        projects.enabled();
        UUID owner = owner(request);
        store.tx.executeWithoutResult(tx -> {
            store.db.queryForMap("select id from users where id=? for update", owner);
            store.project(owner,id);
            int active = store.db.queryForObject("select count(*) from project_instances where project_id=? and owner_id=? and deleted_at is null",Integer.class,id,owner);
            if (active > 0) {
                throw new IllegalArgumentException("Delete the project's instances first");
            }
            store.db.update("update projects set archived=true,deleted_at=now() where id=? and owner_id=?",id,owner);
        });
        return Map.of("ok",true);
    }
    @GetMapping("/{id}/instances") Object instances(@PathVariable UUID id, HttpServletRequest request) {
        projects.enabled();
        store.project(owner(request),id);
        return store.rows("select * from project_instances where project_id=? and owner_id=? and deleted_at is null order by created_at desc",id,owner(request)).stream().map(projects::view).toList();
    }
    @GetMapping("/{id}/instances/{instance}") Object instanceDetails(@PathVariable UUID id, @PathVariable UUID instance, HttpServletRequest request) {
        projects.enabled();
        store.project(owner(request),id);
        var row = store.instance(owner(request),instance);
        if (!row.get("project_id").equals(id)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Not found");
        }
        return projects.view(row);
    }
    @PutMapping("/{id}/instances/{instance}") Object renameInstance(@PathVariable UUID id, @PathVariable UUID instance, @RequestBody JsonNode body, HttpServletRequest request) {
        instanceDetails(id,instance,request);
        store.db.update("update project_instances set name=? where id=? and project_id=? and owner_id=?",Json.required(body,"name",100),instance,id,owner(request));
        return projects.view(store.instance(owner(request),instance));
    }
    @PostMapping("/{id}/instances") Object instance(@PathVariable UUID id, @RequestBody JsonNode body, HttpServletRequest request) {
        return projects.create(owner(request),id,body,null);
    }
    @PostMapping("/{id}/instances/{instance}/fork") Object fork(@PathVariable UUID id, @PathVariable UUID instance, @RequestBody JsonNode body, HttpServletRequest request) {
        return projects.create(owner(request),id,body,instance);
    }
    @PostMapping("/{id}/instances/{instance}/{operation:refresh|archive}") Object maintain(@PathVariable UUID id, @PathVariable UUID instance, @PathVariable String operation, @RequestBody JsonNode body, HttpServletRequest request) {
        projects.maintain(owner(request),id,instance,operation,body);
        return Map.of("ok",true);
    }
    @DeleteMapping("/{id}/instances/{instance}") Object remove(@PathVariable UUID id, @PathVariable UUID instance, HttpServletRequest request) {
        projects.maintain(owner(request),id,instance,"delete",Json.M.createObjectNode());
        return Map.of("ok",true);
    }
    @PostMapping("/compare") Object compare(@RequestBody JsonNode body, HttpServletRequest request) {
        UUID owner = owner(request);
        UUID a = UUID.fromString(Json.required(body,"baselineInstance",36));
        UUID b = UUID.fromString(Json.required(body,"candidateInstance",36));
        var first = store.instance(owner,a);
        var second = store.instance(owner,b);
        if (!first.get("project_id").equals(second.get("project_id"))) {
            throw new IllegalArgumentException("Select two instances of the same project");
        }
        ObjectNode before = body.path("definition").deepCopy();
        ObjectNode after = before.deepCopy();
        before.withObject("/settings").put("instanceId",a.toString());
        after.withObject("/settings").put("instanceId",b.toString());
        return lab.compare(owner,before,after,body.path("input"),body.path("mocks"));
    }
}
