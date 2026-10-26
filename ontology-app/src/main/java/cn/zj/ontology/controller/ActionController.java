package cn.zj.ontology.controller;

import cn.zj.ontology.OntologyProperties;
import cn.zj.ontology.runtime.action.ActionGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 动作接口：propose → approve → execute 三段式。
 * Agent 只能 propose；execute 需要对应对象类型的 EXECUTE 权限。
 */
@RestController
@RequestMapping("/api/actions")
@Tag(name = "动作", description = "唯一允许写业务的入口：先提议、再审批、后执行")
public class ActionController {

    private final ActionGateway gateway;
    private final OntologyProperties properties;
    private final ActorResolver actors;

    public ActionController(ActionGateway gateway, OntologyProperties properties, ActorResolver actors) {
        this.gateway = gateway;
        this.properties = properties;
        this.actors = actors;
    }

    public record ProposeRequest(String targetId, Map<String, Object> params) {
    }

    @PostMapping("/{code}/propose")
    @Operation(summary = "提议一个动作", description = "落 pending_action；无需审批的策略会直接置为 APPROVED")
    public ActionGateway.PendingView propose(HttpServletRequest request,
                                             @PathVariable String code,
                                             @RequestBody ProposeRequest body) {
        return gateway.propose(actors.resolve(request), properties.domain(),
                code, body.targetId(), body.params());
    }

    @PostMapping("/pending/{id}/approve")
    @Operation(summary = "审批通过")
    public ActionGateway.PendingView approve(HttpServletRequest request, @PathVariable String id) {
        return gateway.approve(actors.resolve(request), properties.domain(), id);
    }

    @PostMapping("/pending/{id}/reject")
    @Operation(summary = "驳回")
    public ActionGateway.PendingView reject(HttpServletRequest request, @PathVariable String id) {
        return gateway.reject(actors.resolve(request), properties.domain(), id);
    }

    @PostMapping("/pending/{id}/execute")
    @Operation(summary = "执行已批准的动作")
    public ActionGateway.PendingView execute(HttpServletRequest request, @PathVariable String id) {
        return gateway.execute(actors.resolve(request), properties.domain(), id);
    }

    @GetMapping("/pending")
    @Operation(summary = "查看待处理动作")
    public List<ActionGateway.PendingView> pending(@RequestParam(required = false) String status) {
        return gateway.pending(properties.domain(), status);
    }
}
