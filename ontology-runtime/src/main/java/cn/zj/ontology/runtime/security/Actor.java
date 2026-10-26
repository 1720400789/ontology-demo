package cn.zj.ontology.runtime.security;

import java.util.Map;
import java.util.Set;

/** 调用者。W3 从请求头解析；接 OIDC 后由认证层填充，其余代码不变。 */
public record Actor(String id, String type, Set<String> roles, Map<String, String> attributes) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public String attribute(String name) {
        return attributes.get(name);
    }

    public static Actor system() {
        return new Actor("system", "SERVICE", Set.of(), Map.of());
    }
}
