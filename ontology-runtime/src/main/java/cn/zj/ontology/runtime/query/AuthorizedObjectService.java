package cn.zj.ontology.runtime.query;

import cn.zj.ontology.runtime.security.Actor;
import cn.zj.ontology.runtime.security.ForbiddenException;
import cn.zj.ontology.runtime.security.PermissionService;
import cn.zj.ontology.runtime.security.RowFilterCompiler;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在对象查询外面套一层权限：先判 READ，再把行过滤编译成谓词，最后做字段掩码。 */
@Service
public class AuthorizedObjectService {

    private final ObjectQueryService objects;
    private final PermissionService permissions;

    public AuthorizedObjectService(ObjectQueryService objects, PermissionService permissions) {
        this.objects = objects;
        this.permissions = permissions;
    }

    public ObjectQueryService.ObjectPage list(Actor actor, String domain, String type,
                                              Map<String, String> filters, String fields,
                                              Integer limit, Integer offset) {
        PermissionService.Decision decision = permissions.check(domain, actor, type, "READ");
        if (!decision.allowed()) {
            throw new ForbiddenException("无权读取 " + type + "：" + decision.reason());
        }
        SqlPredicate predicate = decision.rowFilter() == null || decision.rowFilter().isBlank()
                ? null
                : RowFilterCompiler.compile(decision.rowFilter(), actor);

        ObjectQueryService.ObjectPage page =
                objects.list(domain, type, filters, fields, limit, offset, predicate);
        return mask(page, decision.maskedFields());
    }

    public Map<String, Object> get(Actor actor, String domain, String type, String id) {
        PermissionService.Decision decision = permissions.check(domain, actor, type, "READ");
        if (!decision.allowed()) {
            throw new ForbiddenException("无权读取 " + type + "：" + decision.reason());
        }
        SqlPredicate predicate = decision.rowFilter() == null || decision.rowFilter().isBlank()
                ? null
                : RowFilterCompiler.compile(decision.rowFilter(), actor);

        return mask(objects.get(domain, type, id, predicate), decision.maskedFields());
    }

    private ObjectQueryService.ObjectPage mask(ObjectQueryService.ObjectPage page, java.util.Set<String> masked) {
        if (masked.isEmpty()) {
            return page;
        }
        List<Map<String, Object>> items = page.items().stream()
                .map(item -> mask(item, masked))
                .toList();
        return new ObjectQueryService.ObjectPage(page.domain(), page.type(), page.version(),
                page.total(), page.limit(), page.offset(), items);
    }

    private Map<String, Object> mask(Map<String, Object> item, java.util.Set<String> masked) {
        Map<String, Object> copy = new LinkedHashMap<>(item);
        copy.keySet().removeAll(masked);
        return copy;
    }
}
