package cn.zj.ontology.runtime.function;

import java.util.Map;

/** 函数实现。implKey 对应 function_def.impl_key。 */
public interface OntologyFunction {

    String implKey();

    Map<String, Object> apply(FunctionContext context, Map<String, Object> args);
}
