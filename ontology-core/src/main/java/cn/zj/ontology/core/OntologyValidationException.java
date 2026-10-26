package cn.zj.ontology.core;

import java.util.List;

/** 本体定义不合法。消息里带上所有问题，避免改一个报一个。 */
public class OntologyValidationException extends RuntimeException {

    private final List<String> problems;

    public OntologyValidationException(List<String> problems) {
        super("本体定义校验失败（" + problems.size() + " 处）：\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
