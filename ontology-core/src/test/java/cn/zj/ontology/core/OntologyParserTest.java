package cn.zj.ontology.core;

import cn.zj.ontology.core.model.OntologyDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyParserTest {

    private static final String VALID_YAML = """
            apiVersion: ontology/v1
            kind: Domain
            metadata:
              code: demo
              name: 演示域
              owner: demo-team
            spec:
              connector:
                code: demo-pg
                kind: JDBC
              objectTypes:
                - code: site
                  name: 站点
                  primaryKey: id
                  titleProperty: name
                  owner: demo-team
                  binding:
                    sourceKind: TABLE
                    sourceRef: demo.site
                    propertyMapping:
                      siteName: name
                  properties:
                    - code: id
                      name: 站点ID
                      type: STRING
                      required: true
                      owner: demo-team
                    - code: siteName
                      name: 站点名称
                      type: STRING
                      owner: demo-team
              relations: []
              functions: []
              actions: []
              permissionPolicies:
                - subjectType: ROLE
                  subjectId: demo_reader
                  objectType: site
                  action: READ
                  effect: ALLOW
            """;

    @Test
    void parsesYamlIntoRecords() {
        OntologyDefinition def = parse(VALID_YAML);

        assertEquals("demo", def.metadata().code());
        assertEquals(1, def.spec().objectTypes().size());

        OntologyDefinition.ObjectType site = def.spec().objectTypes().get(0);
        assertEquals("site", site.code());
        assertEquals("id", site.primaryKey());
        assertEquals(2, site.properties().size());
        assertEquals("name", site.binding().propertyMapping().get("siteName"));

        OntologyValidator.validate(def);
    }

    @Test
    void rejectsDuplicatePropertyCodes() {
        OntologyDefinition def = parse("""
                apiVersion: ontology/v1
                kind: Domain
                metadata: { code: demo, name: 演示域 }
                spec:
                  connector: { code: c, kind: JDBC }
                  objectTypes:
                    - code: site
                      name: 站点
                      primaryKey: id
                      owner: t
                      binding: { sourceKind: TABLE, sourceRef: t.site }
                      properties:
                        - { code: id, name: A, type: STRING, owner: t }
                        - { code: id, name: B, type: STRING, owner: t }
                """);

        OntologyValidationException ex = assertThrows(OntologyValidationException.class,
                () -> OntologyValidator.validate(def));
        assertTrue(ex.getMessage().contains("属性 code 重复"), ex.getMessage());
    }

    @Test
    void rejectsPrimaryKeyMissingFromProperties() {
        OntologyDefinition def = parse("""
                apiVersion: ontology/v1
                kind: Domain
                metadata: { code: demo, name: 演示域 }
                spec:
                  connector: { code: c, kind: JDBC }
                  objectTypes:
                    - code: site
                      name: 站点
                      primaryKey: code
                      owner: t
                      binding: { sourceKind: TABLE, sourceRef: t.site }
                      properties:
                        - { code: id, name: A, type: STRING, owner: t }
                """);

        OntologyValidationException ex = assertThrows(OntologyValidationException.class,
                () -> OntologyValidator.validate(def));
        assertTrue(ex.getMessage().contains("primaryKey 'code'"), ex.getMessage());
    }

    private OntologyDefinition parse(String yaml) {
        return new OntologyParser().parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
