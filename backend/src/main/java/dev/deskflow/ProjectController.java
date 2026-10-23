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
        return store.rows("select id,name,kind,created_at,archived from projects where owner_id=? order by created_at desc limit 100",owner(request));
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
        store.project(owner(request),id);
        store.db.update("update projects set name=?,archived=? where id=? and owner_id=?",Json.required(body,"name",100),body.path("archived").asBoolean(),id,owner(request));
        return projects.view(store.project(owner(request),id));
    }
    @DeleteMapping("/{id}") Object delete(@PathVariable UUID id, HttpServletRequest request) {
        projects.enabled();
        store.project(owner(request),id);
        int active = store.db.queryForObject("select count(*) from project_instances where project_id=? and owner_id=? and status<>'ARCHIVED'",Integer.class,id,owner(request));
        if (active > 0) { throw new IllegalArgumentException("Archive or delete instances first"); }
        store.db.update("update projects set archived=true where id=? and owner_id=?",id,owner(request));
        return Map.of("ok",true);
    }
    @GetMapping("/{id}/instances") Object instances(@PathVariable UUID id, HttpServletRequest request) {
        projects.enabled();
        store.project(owner(request),id);
        return store.rows("select id,project_id,name,ref,status,state_fingerprint,base_instance_id,created_at from project_instances where project_id=? and owner_id=? order by created_at desc",id,owner(request));
    }
    @GetMapping("/{id}/instances/{instance}") Object instanceDetails(@PathVariable UUID id, @PathVariable UUID instance, HttpServletRequest request) {
        projects.enabled();
        store.project(owner(request),id);
        var row = store.instance(owner(request),instance);
        if (!row.get("project_id").equals(id)) { throw new IllegalArgumentException("Instance does not belong to this project"); }
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
