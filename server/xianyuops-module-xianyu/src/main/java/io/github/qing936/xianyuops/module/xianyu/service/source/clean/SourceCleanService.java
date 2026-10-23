package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils;
import io.github.qing936.xianyuops.framework.tenant.core.context.TenantContextHolder;
import io.github.qing936.xianyuops.framework.tenant.core.util.TenantUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCleanSkillRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCleanCategoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanStatusRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanRecommendRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceCleanTaskDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceInventoryDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceCleanTaskMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceInventoryMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SourceCleanService {
    private final SourceCleanProperties properties;
    private final SourceInventoryMapper inventories;
    private final SourceCleanTaskMapper tasks;
    private final SourceCleanSkills skills;
    private final SourceCleanDifyClient dify;
    private final SourceCleanResult resultHandler;
    private final SourceCleanRecommendationParser recommendationParser;
    private final TransactionTemplate transaction;

    public List<SourceCleanSkillRespVO> skillOptions() {
        return skills.listOptions();
    }

    public SourceInventoryCleanRecommendRespVO recommend(List<Long> inventoryIds) {
        validateInventoryIds(inventoryIds);
        List<SourceInventoryDO> rows = new ArrayList<>();
        for (Long id : inventoryIds) {
            SourceInventoryDO row = inventories.selectById(id);
            if (row == null) {
                throw exception(SOURCE_INVENTORY_NOT_EXISTS);
            }
            rows.add(row);
        }

        ObjectNode catalog = skills.safeCatalog();
        ObjectNode inputs = (ObjectNode) JsonUtils.parseTree("{}");
        ArrayNode products = (ArrayNode) JsonUtils.parseTree("[]");
        for (SourceInventoryDO row : rows) {
            ObjectNode product = products.addObject();
            product.put("inventory_id", row.getId().toString());
            product.put("title", truncate(Objects.toString(row.getTitle(), ""), 200));
            product.put("description", truncate(Objects.toString(row.getDescription(), ""), 2000));
            product.put("search_keyword", Objects.toString(row.getSearchKeyword(), ""));
            product.put("brand", Objects.toString(row.getBrand(), ""));
            product.put("item_condition", Objects.toString(row.getItemCondition(), ""));
            product.put("price_yuan", row.getPrice() == null ? ""
                    : BigDecimal.valueOf(row.getPrice(), 2).toPlainString());
            product.put("image_count", parseStringList(row.getImages()).size());
            product.put("has_original_image", mainImage(row) != null);
        }
        inputs.put("products_json", products.toString());
        inputs.put("skill_catalog_json", catalog.toString());

        String traceId = "source-clean-recommend-" + UUID.randomUUID();
        ObjectNode modelResult = (ObjectNode) JsonUtils.parseTree("{\"recommendations\":[]}");
        if (properties.getRecommendationApiKey() != null
                && !properties.getRecommendationApiKey().isBlank()) {
            try {
                modelResult = dify.runRecommendation(inputs,
                        "tenant-" + TenantContextHolder.getRequiredTenantId()
                                + "-user-" + SecurityFrameworkUtils.getLoginUserId(), traceId);
            } catch (RuntimeException e) {
                log.warn("[sourceCleanRecommend][traceId={} failureType={}]", traceId,
                        e.getClass().getSimpleName());
            }
        }
        ObjectNode normalized = recommendationParser.parse(modelResult, inventoryIds, catalog);
        return recommendationResponse(traceId, rows, normalized);
    }

    private SourceInventoryCleanRecommendRespVO recommendationResponse(
            String traceId, List<SourceInventoryDO> rows, ObjectNode normalized) {
        Map<String, JsonNode> normalizedById = new HashMap<>();
        for (JsonNode item : normalized.path("recommendations")) {
            normalizedById.put(item.path("inventory_id").asText(), item);
        }
        List<SourceInventoryCleanRecommendRespVO.Item> items = new ArrayList<>();
        for (SourceInventoryDO row : rows) {
            JsonNode normalizedItem = normalizedById.get(row.getId().toString());
            SourceInventoryCleanRecommendRespVO.Item item = new SourceInventoryCleanRecommendRespVO.Item();
            item.setInventoryId(row.getId());
            item.setTitle(row.getTitle());
            item.setMainImageUrl(mainImage(row));
            item.setHasOriginalImage(item.getMainImageUrl() != null);
            item.setWarnings(stringList(normalizedItem.path("warnings")));
            JsonNode category = normalizedItem.path("routing_category");
            if (category.isObject()) {
                SourceInventoryCleanRecommendRespVO.CategoryChoice choice =
                        new SourceInventoryCleanRecommendRespVO.CategoryChoice();
                choice.setCategoryCode(category.path("category_code").asText());
                choice.setCategoryName(category.path("category_name").asText());
                choice.setConfidence(category.path("confidence").asDouble());
                choice.setReason(category.path("reason").asText());
                item.setRoutingCategory(choice);
            }
            Map<String, SourceInventoryCleanRecommendRespVO.SkillChoice> choices = new HashMap<>();
            for (String field : Set.of("title", "description", "images")) {
                JsonNode value = normalizedItem.path("recommendations").path(field);
                if (value.isObject()) {
                    SourceInventoryCleanRecommendRespVO.SkillChoice choice =
                            new SourceInventoryCleanRecommendRespVO.SkillChoice();
                    choice.setSkillCode(value.path("skill_code").asText());
                    choice.setConfidence(value.path("confidence").asDouble());
                    choice.setReason(value.path("reason").asText());
                    choices.put(field, choice);
                }
            }
            item.setRecommendations(choices);
            item.setReferenceOriginalImageRecommended(item.getHasOriginalImage()
                    && normalizedItem.path("reference_original_image_recommended").asBoolean(false));
            items.add(item);
        }
        SourceInventoryCleanRecommendRespVO response = new SourceInventoryCleanRecommendRespVO();
        response.setRecommendationTraceId(traceId);
        response.setCatalogVersion(normalized.path("catalog_version").asText());
        response.setItems(items);
        response.setSkills(skills.listOptions());
        response.setCategories(skills.primaryCategories());
        return response;
    }

    private void validateInventoryIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) {
            throw exception(SOURCE_CLEAN_INVALID_REQUEST);
        }
    }

    private String truncate(String value, int maxCodePoints) {
        return value.codePointCount(0, value.length()) <= maxCodePoints
                ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private String mainImage(SourceInventoryDO row) {
        if (isHttpUrl(row.getPicUrl())) {
            return row.getPicUrl().trim();
        }
        return parseStringList(row.getImages()).stream().filter(this::isHttpUrl).findFirst().orElse(null);
    }

    private boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            String scheme = URI.create(value.trim()).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    public SourceInventoryCleanSubmitRespVO submit(SourceInventoryCleanSubmitReqVO request) {
        if (!validRequestId(request.getClientRequestId()) || request.getItems() == null
                || request.getItems().isEmpty()) {
            throw exception(SOURCE_CLEAN_INVALID_REQUEST);
        }
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        SourceCleanTaskDO existing = tasks.selectByRequestId(tenantId, userId, request.getClientRequestId());
        if (existing != null) {
            return taskResponse(existing);
        }
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw exception(SOURCE_CLEAN_NOT_CONFIGURED);
        }
        if (request.getCatalogVersion() != null && !request.getCatalogVersion().isBlank()
                && !request.getCatalogVersion().equals(skills.catalogVersion())) {
            throw exception(SOURCE_CLEAN_INVALID_REQUEST);
        }
        List<Long> ids = request.getItems().stream()
                .map(SourceInventoryCleanSubmitReqVO.Item::getInventoryId).toList();
        validateInventoryIds(ids);
        try {
            return transaction.execute(status -> createTask(request, tenantId, userId));
        } catch (DuplicateKeyException e) {
            SourceCleanTaskDO duplicated = tasks.selectByRequestId(tenantId, userId, request.getClientRequestId());
            if (duplicated != null) {
                return taskResponse(duplicated);
            }
            throw e;
        }
    }

    private SourceInventoryCleanSubmitRespVO createTask(
            SourceInventoryCleanSubmitReqVO request, Long tenantId, Long userId) {
        Map<Long, SourceInventoryDO> rows = new HashMap<>();
        for (Long id : request.getItems().stream().map(SourceInventoryCleanSubmitReqVO.Item::getInventoryId)
                .sorted().toList()) {
            SourceInventoryDO row = inventories.selectByIdForUpdate(id);
            if (row == null) {
                throw exception(SOURCE_INVENTORY_NOT_EXISTS);
            }
            if (Integer.valueOf(1).equals(row.getCleanStatus())) {
                throw exception(SOURCE_CLEAN_BUSY);
            }
            rows.put(id, row);
        }

        ArrayNode inputs = (ArrayNode) JsonUtils.parseTree("[]");
        for (SourceInventoryCleanSubmitReqVO.Item item : request.getItems()) {
            List<String> fields = normalizeFields(item);
            SourceCleanCategoryRespVO category = skills.category(item.getCategoryCode())
                    .orElseThrow(() -> exception(SOURCE_CLEAN_INVALID_REQUEST));
            ObjectNode skillSnapshot;
            try {
                skillSnapshot = skills.resolve(fields, item.getSkills(), null);
            } catch (IllegalArgumentException e) {
                throw exception(SOURCE_CLEAN_INVALID_REQUEST);
            }
            if (!skills.compatible(skillSnapshot, item.getCategoryCode())) {
                throw exception(SOURCE_CLEAN_INVALID_REQUEST);
            }
            SourceInventoryDO row = rows.get(item.getInventoryId());
            inputs.add(buildInput(row, item, category, fields, skillSnapshot, request));
            SourceInventoryDO update = new SourceInventoryDO();
            update.setId(row.getId());
            update.setCleanStatus(1);
            update.setCategoryName(category.getCategoryName());
            inventories.updateById(update);
        }
        SourceCleanTaskDO task = new SourceCleanTaskDO();
        task.setTenantId(tenantId);
        task.setUserId(userId);
        task.setRequestId(request.getClientRequestId());
        task.setStatus(0);
        task.setTotalCount(request.getItems().size());
        task.setCompletedCount(0);
        task.setFailedCount(0);
        task.setInputs(inputs.toString());
        task.setResults("{}");
        tasks.insert(task);
        log.info("[sourceClean][任务已入队 taskId={} itemCount={}]", task.getId(), task.getTotalCount());
        return taskResponse(task);
    }

    private List<String> normalizeFields(SourceInventoryCleanSubmitReqVO.Item item) {
        List<String> fields = item.getCleanTypes();
        if (item.getInventoryId() == null || item.getInventoryId() <= 0 || item.getCategoryCode() == null
                || item.getCategoryCode().isBlank() || fields == null || fields.isEmpty()
                || fields.size() != new HashSet<>(fields).size()
                || fields.stream().anyMatch(field -> !Set.of("title", "description", "images").contains(field))
                || item.getSkills() == null || !item.getSkills().keySet().equals(new HashSet<>(fields))
                || item.getSkills().values().stream().anyMatch(code -> code == null || code.isBlank())
                || item.getReferenceOriginalImage() == null
                || (item.getReferenceOriginalImage() && !fields.contains("images"))) {
            throw exception(SOURCE_CLEAN_INVALID_REQUEST);
        }
        return new ArrayList<>(new LinkedHashSet<>(fields));
    }

    private ObjectNode buildInput(SourceInventoryDO row, SourceInventoryCleanSubmitReqVO.Item item,
                                  SourceCleanCategoryRespVO category, List<String> fields,
                                  ObjectNode snapshot, SourceInventoryCleanSubmitReqVO request) {
        String title = Objects.toString(row.getTitle(), "");
        String description = Objects.toString(row.getDescription(), "");
        if ((fields.contains("title") || fields.contains("description")) && title.isBlank() && description.isBlank()) {
            throw exception(SOURCE_CLEAN_INVALID_REQUEST);
        }
        ObjectNode input = (ObjectNode) JsonUtils.parseTree("{}");
        input.put("schema_version", "1.2");
        input.put("inventory_id", row.getId().toString());
        input.put("category_code", category.getCategoryCode());
        input.put("category_name", category.getCategoryName());
        input.put("title", title);
        input.put("description", description);
        boolean reference = Boolean.TRUE.equals(item.getReferenceOriginalImage());
        String mainImage = mainImage(row);
        if (reference && mainImage == null) {
            throw exception(SOURCE_CLEAN_INVALID_REQUEST);
        }
        input.put("images", reference ? JsonUtils.toJsonString(List.of(mainImage)) : "[]");
        input.put("clean_types", JsonUtils.toJsonString(fields));
        input.put("skills", snapshot.toString());
        input.put("reference_original_image", String.valueOf(reference));
        input.put("recommendation_trace_id", Objects.toString(request.getRecommendationTraceId(), ""));
        // 用户补充要求（批级，整批商品共用），空串表示未填写
        input.put("requirement_title", Objects.toString(request.getRequirementTitle(), ""));
        input.put("requirement_description", Objects.toString(request.getRequirementDescription(), ""));
        input.put("requirement_images", Objects.toString(request.getRequirementImages(), ""));
        return input;
    }

    private boolean validRequestId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private SourceInventoryCleanSubmitRespVO taskResponse(SourceCleanTaskDO task) {
        SourceInventoryCleanSubmitRespVO response = new SourceInventoryCleanSubmitRespVO();
        response.setTaskId(task.getId());
        response.setTotalCount(task.getTotalCount());
        response.setMessage(task.getStatus() != null && task.getStatus() == 0 ? "清洗任务已排队" : "清洗任务已存在");
        return response;
    }

    public SourceInventoryCleanStatusRespVO status(Long id) {
        SourceCleanTaskDO task = tasks.selectById(id);
        if (task == null || !Objects.equals(task.getUserId(), SecurityFrameworkUtils.getLoginUserId())) {
            throw exception(SOURCE_CLEAN_TASK_NOT_EXISTS);
        }
        SourceInventoryCleanStatusRespVO result = new SourceInventoryCleanStatusRespVO();
        result.setTaskId(id);
        result.setStatus(task.getStatus());
        result.setTotalCount(task.getTotalCount());
        result.setCompletedCount(task.getCompletedCount());
        result.setFailedCount(task.getFailedCount());
        result.setProgress((task.getCompletedCount() + task.getFailedCount()) * 100 / task.getTotalCount());
        result.setErrorMessage(task.getErrorMessage());
        result.setResults(JsonUtils.parseTree(task.getResults()));
        return result;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode node = JsonUtils.parseTree(json);
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    /** 跨租户扫描仅用于后台队列；每项业务操作切回任务所属租户。 */
    public void poll() {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return;
        }
        List<SourceCleanTaskDO> stale = TenantUtils.executeIgnore(() -> tasks.selectList(
                new LambdaQueryWrapperX<SourceCleanTaskDO>().eq(SourceCleanTaskDO::getStatus, 1)
                        .lt(SourceCleanTaskDO::getLeaseUntil, LocalDateTime.now()).last("LIMIT 20")));
        for (SourceCleanTaskDO task : stale) {
            TenantUtils.execute(task.getTenantId(), () -> expire(task.getId()));
        }
        List<SourceCleanTaskDO> pending = TenantUtils.executeIgnore(() -> tasks.selectList(
                new LambdaQueryWrapperX<SourceCleanTaskDO>().eq(SourceCleanTaskDO::getStatus, 0)
                        .orderByAsc(SourceCleanTaskDO::getId).last("LIMIT 1")));
        for (SourceCleanTaskDO task : pending) {
            TenantUtils.execute(task.getTenantId(), () -> execute(task));
        }
    }

    private void execute(SourceCleanTaskDO task) {
        // 为 Dify + 最多10张图片下载留足时间；崩溃后过期标失败，不重放收费调用。
        LocalDateTime lease = LocalDateTime.now().plusSeconds(
                (long) task.getTotalCount() * (properties.getTimeoutSeconds() + 400) + 600);
        int claimed = tasks.update(null, new LambdaUpdateWrapper<SourceCleanTaskDO>()
                .eq(SourceCleanTaskDO::getId, task.getId()).eq(SourceCleanTaskDO::getStatus, 0)
                .set(SourceCleanTaskDO::getStatus, 1).set(SourceCleanTaskDO::getLeaseUntil, lease));
        if (claimed == 0) {
            log.info("[sourceClean][任务领取失败 taskId={} reason=already_claimed]", task.getId());
            return;
        }
        log.info("[sourceClean][开始执行任务 taskId={} itemCount={}]", task.getId(), task.getTotalCount());
        for (JsonNode input : JsonUtils.parseTree(task.getInputs())) {
            SourceCleanTaskDO current = tasks.selectById(task.getId());
            if (current == null || current.getStatus() != 1 || LocalDateTime.now().isAfter(lease)) {
                log.warn("[sourceClean][任务提前终止 taskId={} inventoryId={} currentStatus={} leaseExpired={}]",
                        task.getId(), input.path("inventory_id").asText(),
                        current == null ? null : current.getStatus(), LocalDateTime.now().isAfter(lease));
                return;
            }
            String traceId = "source-clean-" + task.getId() + "-" + input.path("inventory_id").asText();
            log.info("[sourceClean][开始处理商品 taskId={} inventoryId={} traceId={}]",
                    task.getId(), input.path("inventory_id").asText(), traceId);
            ObjectNode result = null;
            String error = null;
            String cleanHtml = null;
            try {
                result = resultHandler.validateAndStore(dify.run(input,
                        "tenant-" + task.getTenantId() + "-user-" + task.getUserId(), traceId), input);
                // HTML 源码单独入库，避免混入 clean_config 触发存储大小限制
                cleanHtml = result.path("cleaned_html").asText(null);
                result.remove("cleaned_html");
                if (result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) {
                    throw new SourceCleanFailure("清洗结果超过存储大小限制");
                }
            } catch (Exception e) {
                // 仅输出异常类型与追踪号，不记录模型响应、Key、签名URL。
                log.warn("[sourceClean][traceId={} failureType={}]", traceId, e.getClass().getSimpleName());
                error = (e instanceof SourceCleanFailure ? e.getMessage() : "清洗结果处理失败") + "，追踪号：" + traceId;
                result = null;
            }
            saveItem(task.getId(), input, result, error, cleanHtml);
        }
    }

    private void saveItem(Long taskId, JsonNode input, ObjectNode result, String error, String cleanHtml) {
        for (int attempt = 1; ; attempt++) {
            try {
                transaction.executeWithoutResult(status -> {
                    SourceCleanTaskDO task = tasks.lock(taskId);
                    if (task == null || task.getStatus() != 1
                            || task.getLeaseUntil().isBefore(LocalDateTime.now())) {
                        return;
                    }
                    String id = input.path("inventory_id").asText();
                    ObjectNode results = (ObjectNode) JsonUtils.parseTree(task.getResults());
                    SourceInventoryDO row = inventories.selectByIdForUpdate(Long.valueOf(id));
                    if (results.has(id)) {
                        return;
                    }
                    boolean success = result != null && row != null;
                    if (row != null) {
                        SourceInventoryDO update = new SourceInventoryDO();
                        update.setId(row.getId());
                        update.setCleanStatus(success ? 2 : 3);
                        if (success) {
                            JsonNode fields = JsonUtils.parseTree(input.path("clean_types").asText());
                            for (JsonNode field : fields) {
                                switch (field.asText()) {
                                    case "title" -> update.setTitle(result.path("cleaned_title").asText());
                                    case "description" -> update.setDescription(result.path("cleaned_description").asText());
                                    case "images" -> {
                                        String imagesJson = replaceFirstImage(row.getImages(), result.path("cleaned_images").toString());
                                        update.setImages(imagesJson);
                                        update.setPicUrl(resolvePicUrl(row, imagesJson));
                                    }
                                    default -> throw new IllegalStateException("未知字段");
                                }
                            }
                            update.setCleanTime(LocalDateTime.now());
                            update.setCleanConfig(result.toString());
                            update.setCleanHtml(cleanHtml);
                        }
                        inventories.updateById(update);
                    }
                    if (success) {
                        results.set(id, result);
                        task.setCompletedCount(task.getCompletedCount() + 1);
                    } else {
                        String message = error == null ? "货源已删除" : error;
                        results.putObject(id).put("status", "failed").put("error", message);
                        task.setErrorMessage(message);
                        task.setFailedCount(task.getFailedCount() + 1);
                    }
                    task.setResults(results.toString());
                    if (task.getCompletedCount() + task.getFailedCount() == task.getTotalCount()) {
                        task.setStatus(task.getFailedCount() == 0 ? 2 : 3);
                    }
                    tasks.updateById(task);
                });
                return;
            } catch (CannotCreateTransactionException e) {
                if (attempt >= 4) {
                    throw e;
                }
                log.warn("[sourceClean][保存结果时数据库连接不可用 taskId={} inventoryId={} attempt={}/4]",
                        taskId, input.path("inventory_id").asText(), attempt);
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
    }

    /** 头图清洗仅替换首图，保留原商品其余图片。 */
    private String replaceFirstImage(String originalImagesJson, String cleanedImagesJson) {
        List<String> images = new ArrayList<>(parseStringList(originalImagesJson));
        String cleanedImage = parseStringList(cleanedImagesJson).get(0);
        if (images.isEmpty()) {
            images.add(cleanedImage);
        } else {
            images.set(0, cleanedImage);
        }
        return JsonUtils.toJsonString(images);
    }

    private String resolvePicUrl(SourceInventoryDO row, String imagesJson) {
        List<String> images = parseStringList(imagesJson);
        return images.isEmpty() ? row.getPicUrl() : images.get(0);
    }

    private void expire(Long taskId) {
        transaction.executeWithoutResult(status -> {
            SourceCleanTaskDO task = tasks.lock(taskId);
            if (task == null || task.getStatus() != 1 || !task.getLeaseUntil().isBefore(LocalDateTime.now())) {
                return;
            }
            ObjectNode results = (ObjectNode) JsonUtils.parseTree(task.getResults());
            for (JsonNode input : JsonUtils.parseTree(task.getInputs())) {
                String id = input.path("inventory_id").asText();
                if (!results.has(id)) {
                    inventories.update(null, new LambdaUpdateWrapper<SourceInventoryDO>()
                            .eq(SourceInventoryDO::getId, Long.valueOf(id)).eq(SourceInventoryDO::getCleanStatus, 1)
                            .set(SourceInventoryDO::getCleanStatus, 3));
                    results.putObject(id).put("status", "failed").put("error", "执行超时或服务中断，请检查Dify运行记录后手动重试");
                }
            }
            task.setResults(results.toString());
            task.setFailedCount(task.getTotalCount() - task.getCompletedCount());
            task.setStatus(3);
            task.setErrorMessage("执行超时或服务中断，未自动重放工作流");
            tasks.updateById(task);
        });
    }
}
