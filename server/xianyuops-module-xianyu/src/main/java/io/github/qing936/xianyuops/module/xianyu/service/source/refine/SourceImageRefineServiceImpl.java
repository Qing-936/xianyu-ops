package io.github.qing936.xianyuops.module.xianyu.service.source.refine;

import cn.hutool.core.util.StrUtil;
import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceInventoryDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceInventoryMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.redis.RedisKeyConstants;
import io.github.qing936.xianyuops.module.xianyu.service.source.clean.SourceCleanImageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceImageRefineErrorCodeConstants.SOURCE_IMAGE_REFINE_NOT_CONFIGURED;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceImageRefineErrorCodeConstants.SOURCE_IMAGE_REFINE_NO_CLEAN_HTML;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceImageRefineErrorCodeConstants.SOURCE_IMAGE_REFINE_PREVIEW_EXPIRED;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskErrorCodeConstants.SOURCE_INVENTORY_NOT_EXISTS;

@Service
@Validated
@Slf4j
public class SourceImageRefineServiceImpl implements SourceImageRefineService {

    /** 预览结果保留时长，超时需重新生成 */
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    @Resource
    private SourceInventoryMapper sourceInventoryMapper;

    @Resource
    private SourceImageRefineProperties properties;

    @Resource
    private SourceImageRefineDifyClient difyClient;

    @Resource
    private SourceCleanImageStore sourceImageStore;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String preview(Long inventoryId, String prompt, Long userId) {
        if (!properties.isEnabled() || StrUtil.isBlank(properties.getApiKey())) {
            throw exception(SOURCE_IMAGE_REFINE_NOT_CONFIGURED);
        }
        SourceInventoryDO inventory = sourceInventoryMapper.selectById(inventoryId);
        if (inventory == null) {
            throw exception(SOURCE_INVENTORY_NOT_EXISTS);
        }
        if (StrUtil.isBlank(inventory.getCleanHtml())) {
            throw exception(SOURCE_IMAGE_REFINE_NO_CLEAN_HTML);
        }
        String traceId = "source-refine-" + inventoryId + "-" + System.currentTimeMillis();
        SourceImageRefineDifyClient.RefineResult result =
                difyClient.refine(inventory.getCleanHtml(), prompt, "user-" + userId, traceId);
        // 工作流产出的 Dify 临时文件地址仅短暂有效，立即下载转 JPEG 后转存 OSS
        String imageUrl = sourceImageStore.storeRefinedImage(result.imageUrl());
        ObjectNode pending = (ObjectNode) JsonUtils.parseTree("{}");
        pending.put("imageUrl", imageUrl);
        if (StrUtil.isNotBlank(result.newHtml())) {
            pending.put("newHtml", result.newHtml());
        }
        stringRedisTemplate.opsForValue().set(pendingKey(inventoryId), pending.toString(), PREVIEW_TTL);
        return imageUrl;
    }

    @Override
    public void apply(Long inventoryId) {
        SourceInventoryDO inventory = sourceInventoryMapper.selectById(inventoryId);
        if (inventory == null) {
            throw exception(SOURCE_INVENTORY_NOT_EXISTS);
        }
        String key = pendingKey(inventoryId);
        String pending = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(pending)) {
            throw exception(SOURCE_IMAGE_REFINE_PREVIEW_EXPIRED);
        }
        JsonNode pendingNode = JsonUtils.parseTree(pending);
        String imageUrl = pendingNode.path("imageUrl").asText();
        String newHtml = pendingNode.path("newHtml").asText(null);
        List<String> images = StrUtil.isBlank(inventory.getImages())
                ? new ArrayList<>() : new ArrayList<>(JsonUtils.parseArray(inventory.getImages(), String.class));
        if (images.isEmpty()) {
            images.add(imageUrl);
        } else {
            images.set(0, imageUrl);
        }
        SourceInventoryDO update = new SourceInventoryDO();
        update.setId(inventoryId);
        update.setImages(JsonUtils.toJsonString(images));
        update.setPicUrl(imageUrl);
        if (StrUtil.isNotBlank(newHtml)) {
            update.setCleanHtml(newHtml);
        }
        sourceInventoryMapper.updateById(update);
        stringRedisTemplate.delete(key);
        log.info("[SourceImageRefine][inventoryId={} 已应用微调主图]", inventoryId);
    }

    private String pendingKey(Long inventoryId) {
        return RedisKeyConstants.SOURCE_IMAGE_REFINE_PREVIEW + ":" + inventoryId;
    }
}
