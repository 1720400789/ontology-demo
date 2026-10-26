package cn.zj.ontology.controller;

import cn.zj.ontology.OntologyProperties;
import cn.zj.ontology.runtime.function.FunctionEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 函数接口：列出可调用的只读计算，以及调用它们。 */
@RestController
@RequestMapping("/api/functions")
@Tag(name = "函数", description = "只读业务计算；Agent 的数值必须来自这里，不能自己算")
public class FunctionController {

    private final FunctionEngine engine;
    private final OntologyProperties properties;

    public FunctionController(FunctionEngine engine, OntologyProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @GetMapping
    @Operation(summary = "列出已发布函数及其入参 schema")
    public List<FunctionEngine.FunctionSummary> list() {
        return engine.list(properties.domain());
    }

    @PostMapping("/{code}/invoke")
    @Operation(summary = "调用函数", description = "请求体即入参 JSON，返回结果与耗时")
    public FunctionEngine.FunctionResult invoke(@PathVariable String code,
                                                @RequestBody(required = false) Map<String, Object> args) {
        return engine.invoke(properties.domain(), code, args);
    }
}
