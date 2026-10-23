package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 货源 AI 清洗推荐响应 VO")
@Data
public class SourceInventoryCleanRecommendRespVO {
    private String recommendationTraceId;
    private String catalogVersion;
    private List<Item> items;
    private List<SourceCleanSkillRespVO> skills;
    private List<SourceCleanCategoryRespVO> categories;

    @Data
    public static class Item {
        private Long inventoryId;
        private String title;
        private String mainImageUrl;
        private Boolean hasOriginalImage;
        private CategoryChoice routingCategory;
        private Map<String, SkillChoice> recommendations;
        private Boolean referenceOriginalImageRecommended;
        private List<String> warnings;
    }

    @Data
    public static class CategoryChoice {
        private String categoryCode;
        private String categoryName;
        private Double confidence;
        private String reason;
    }

    @Data
    public static class SkillChoice {
        private String skillCode;
        private Double confidence;
        private String reason;
    }
}
