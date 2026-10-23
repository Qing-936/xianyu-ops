package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("货源 AI Skill 推荐结果校验")
class SourceCleanRecommendationParserTest {

    @Test
    @DisplayName("只接受目录中存在且与推荐分类兼容的 Skill")
    void keepsOnlyCompatibleCatalogChoices() throws Exception {
        Class<?> parserClass = assertDoesNotThrow(() -> Class.forName(
                "io.github.qing936.xianyuops.module.xianyu.service.source.clean.SourceCleanRecommendationParser"));
        Object parser = parserClass.getConstructor().newInstance();
        Method parse = parserClass.getMethod("parse", ObjectNode.class, List.class, ObjectNode.class);

        ObjectNode normalized = (ObjectNode) parse.invoke(parser, object("""
                {
                  "recommendations":[{
                    "inventory_id":"43",
                    "category":{"category_code":"physical.children_toy","confidence":0.9,"reason":"积木"},
                    "title":{"skill_code":"title_scene_value","confidence":0.8,"reason":"场景明确"},
                    "description":{"skill_code":"desc_electronic_material","confidence":0.8,"reason":"错误类型"},
                    "images":{"skill_code":"image_children_toy","confidence":0.9,"reason":"童趣"},
                    "reference_original_image_recommended":true
                  }]
                }
                """), List.of(43L), catalog());

        JsonNode item = normalized.path("recommendations").get(0);
        assertEquals("physical.children_toy", item.path("routing_category").path("category_code").asText());
        assertTrue(item.path("recommendations").has("title"));
        assertTrue(item.path("recommendations").has("images"));
        assertFalse(item.path("recommendations").has("description"));
        assertFalse(item.path("warnings").isEmpty());
    }

    @Test
    @DisplayName("模型遗漏商品时返回可人工选择的空推荐行")
    void addsManualFallbackForMissingInventory() throws Exception {
        Class<?> parserClass = Class.forName(
                "io.github.qing936.xianyuops.module.xianyu.service.source.clean.SourceCleanRecommendationParser");
        Object parser = parserClass.getConstructor().newInstance();
        Method parse = parserClass.getMethod("parse", ObjectNode.class, List.class, ObjectNode.class);

        ObjectNode normalized = (ObjectNode) parse.invoke(parser,
                object("{\"recommendations\":[]}"), List.of(44L), catalog());

        JsonNode item = normalized.path("recommendations").get(0);
        assertEquals("44", item.path("inventory_id").asText());
        assertTrue(item.path("recommendations").isEmpty());
        assertFalse(item.path("warnings").isEmpty());
    }

    @Test
    @DisplayName("中文分类名会归一化为目录中的分类编码")
    void normalizesCatalogCategoryNameToCode() throws Exception {
        SourceCleanRecommendationParser parser = new SourceCleanRecommendationParser();

        ObjectNode normalized = parser.parse(object("""
                {
                  "recommendations":[{
                    "inventory_id":"45",
                    "category":{"category_code":"儿童玩具","confidence":0.9,"reason":"积木"},
                    "title":{"skill_code":"title_scene_value","confidence":0.8,"reason":"场景明确"},
                    "images":{"skill_code":"image_children_toy","confidence":0.9,"reason":"童趣"}
                  }]
                }
                """), List.of(45L), catalog());

        JsonNode item = normalized.path("recommendations").get(0);
        assertEquals("physical.children_toy", item.path("routing_category").path("category_code").asText());
        assertTrue(item.path("recommendations").has("title"));
        assertTrue(item.path("recommendations").has("images"));
        assertFalse(item.path("warnings").toString().contains("不存在的分类"));
    }

    @Test
    @DisplayName("目录中不存在的分类仍然拒绝")
    void rejectsUnknownCategory() {
        SourceCleanRecommendationParser parser = new SourceCleanRecommendationParser();

        ObjectNode normalized = parser.parse(object("""
                {
                  "recommendations":[{
                    "inventory_id":"46",
                    "category":{"category_code":"教材教辅类","confidence":0.9,"reason":"教材"},
                    "title":{"skill_code":"title_scene_value","confidence":0.8,"reason":"学习场景"}
                  }]
                }
                """), List.of(46L), catalog());

        JsonNode item = normalized.path("recommendations").get(0);
        assertTrue(item.path("routing_category").isNull());
        assertFalse(item.path("recommendations").has("title"));
        assertTrue(item.path("warnings").toString().contains("不存在的分类"));
    }

    private static ObjectNode catalog() {
        return object("""
                {
                  "catalog_version":"3.0.0",
                  "categories":[{"category_code":"physical.children_toy","category_name":"儿童玩具"}],
                  "skills":[
                    {"skill_code":"title_scene_value","skill_type":"title","version":"3.0.0","category_scopes":["physical.children_toy"]},
                    {"skill_code":"desc_electronic_material","skill_type":"description","version":"3.0.0","category_scopes":["electronic_material"]},
                    {"skill_code":"image_children_toy","skill_type":"images","version":"3.0.0","category_scopes":["physical.children_toy"]}
                  ]
                }
                """);
    }

    private static ObjectNode object(String json) {
        return (ObjectNode) JsonUtils.parseTree(json);
    }
}
