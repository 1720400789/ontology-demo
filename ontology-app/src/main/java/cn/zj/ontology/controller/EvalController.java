package cn.zj.ontology.controller;

import cn.zj.ontology.OntologyProperties;
import cn.zj.ontology.runtime.eval.EvalRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 评测跑批：本体变更后必须能一键回归。 */
@RestController
@RequestMapping("/api/eval")
@Tag(name = "评测", description = "跑 eval_case，报告通过/失败/跳过")
public class EvalController {

    private final EvalRunner runner;
    private final OntologyProperties properties;

    public EvalController(EvalRunner runner, OntologyProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @PostMapping("/run")
    @Operation(summary = "执行全部启用的评测用例")
    public EvalRunner.Report run() {
        return runner.run(properties.domain());
    }
}
