package cn.zj.ontology;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本体运行时配置。domain 决定 /api/objects/{type} 默认落在哪个业务域。 */
@ConfigurationProperties(prefix = "ontology")
public record OntologyProperties(String domain, Loader loader) {

    public record Loader(boolean enabled, String location) {
    }
}
