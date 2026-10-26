package cn.zj.ontology.controller;

import cn.zj.ontology.runtime.security.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 临时认证：从请求头取调用者身份。接 OIDC 后替换这里即可，下游只认 {@link Actor}。
 *
 * <pre>
 * X-Actor-Id:    u-1001 / maint-agent
 * X-Actor-Type:  USER / AGENT / SERVICE
 * X-Actor-Roles: maint_planner,maint_supervisor
 * X-Actor-Site:  S-001
 * </pre>
 */
@Component
public class ActorResolver {

    public Actor resolve(HttpServletRequest request) {
        String id = header(request, "X-Actor-Id", "anonymous");
        String type = header(request, "X-Actor-Type", "USER").toUpperCase(Locale.ROOT);

        Set<String> roles = Arrays.stream(header(request, "X-Actor-Roles", "").split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toUnmodifiableSet());

        Map<String, String> attributes = new HashMap<>();
        String site = request.getHeader("X-Actor-Site");
        if (site != null && !site.isBlank()) {
            attributes.put("siteId", site);
        }

        return new Actor(id, type, roles, Map.copyOf(attributes));
    }

    private String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
