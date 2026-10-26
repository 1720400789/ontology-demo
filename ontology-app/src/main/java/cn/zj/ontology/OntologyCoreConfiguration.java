package cn.zj.ontology;

import cn.zj.ontology.core.OntologyParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** core 模块不依赖 Spring，这里把无状态工具类注册成 Bean。 */
@Configuration
public class OntologyCoreConfiguration {

    @Bean
    public OntologyParser ontologyParser() {
        return new OntologyParser();
    }
}
