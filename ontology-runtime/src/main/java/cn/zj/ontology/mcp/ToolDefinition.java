package cn.zj.ontology.mcp;

import cn.zj.ontology.runtime.security.Actor;

import java.util.Map;

/** 一个工具：名字、描述、入参 schema、执行逻辑。全部由元模型生成。 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema,
                             Handler handler) {

    public interface Handler {
        Object call(Map<String, Object> arguments, Actor actor);
    }
}
