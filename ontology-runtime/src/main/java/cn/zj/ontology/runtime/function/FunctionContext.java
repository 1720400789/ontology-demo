package cn.zj.ontology.runtime.function;

import java.util.List;
import java.util.Map;

/** 函数只能读，写操作必须走 Action。这里不暴露任何写接口，是结构性约束。 */
public interface FunctionContext {

    Map<String, Object> get(String objectType, String id);

    List<Map<String, Object>> query(String objectType, Map<String, String> filters);
}
