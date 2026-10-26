package cn.zj.ontology.runtime.action;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * 动作实现。只有这里可以写业务库；查询、函数、HTTP 层都没有写接口。
 * 返回结果会原样写进 pending_action.result。
 */
public interface ActionExecutor {

    /** 与 action_def.code 对应。 */
    String actionCode();

    Map<String, Object> execute(Map<String, Object> target, Map<String, Object> params, JdbcTemplate jdbc);
}
