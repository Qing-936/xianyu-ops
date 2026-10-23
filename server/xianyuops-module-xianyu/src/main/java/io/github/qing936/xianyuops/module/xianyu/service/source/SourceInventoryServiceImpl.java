package io.github.qing936.xianyuops.module.xianyu.service.source;

import cn.hutool.core.util.StrUtil;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.common.util.object.BeanUtils;
import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryManualImportRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCleanSkillRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanStatusRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanRecommendRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceInventoryDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceItemDetailDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceInventoryMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceItemDetailMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceBlockedSellerErrorCodeConstants;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskErrorCodeConstants;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceInventoryStatusEnum;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;

@Service
@Validated
@Slf4j
public class SourceInventoryServiceImpl implements SourceInventoryService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private SourceInventoryMapper sourceInventoryMapper;

    @Resource
    private PythonSourceNotifier pythonSourceNotifier;

    @Resource
    private SourceItemDetailMapper sourceItemDetailMapper;

    @Resource
    private SourceBlockedSellerService sourceBlockedSellerService;

    @Resource
    private io.github.qing936.xianyuops.module.xianyu.service.source.clean.SourceCleanImageStore sourceImageStore;

    @Resource
    private io.github.qing936.xianyuops.module.xianyu.service.source.clean.SourceCleanService sourceCleanService;

    @Override
    public void addFromSourceDetail(Long detailId, String inventoryUser, Long tenantId) {
        // 黑名单卖家禁止入库
        SourceItemDetailDO detail = detailId == null ? null : sourceItemDetailMapper.selectById(detailId);
        if (detail != null
                && sourceBlockedSellerService.isBlocked(detail.getSellerId(), detail.getSellerName())) {
            throw exception(SourceBlockedSellerErrorCodeConstants.SOURCE_BLOCKED_SELLER_CANNOT_INVENTORY);
        }
        List<SourceInventoryDO> existingInventories = detail == null ? List.of()
                : StrUtil.isNotBlank(detail.getExternalItemId())
                ? sourceInventoryMapper.selectListByExternalItemIds(List.of(detail.getExternalItemId()))
                : sourceInventoryMapper.selectListBySourceDetailIds(List.of(detailId));
        SourceInventoryDO existingInventory = existingInventories.stream()
                .max(Comparator.comparing(SourceInventoryDO::getId)).orElse(null);
        if (existingInventory != null) {
            if (existingInventory.getXianyuProductId() != null
                    || (existingInventory.getInventoryStatus() != null
                    && existingInventory.getInventoryStatus() == SourceInventoryStatusEnum.PRODUCT_CREATED.getStatus())) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_REFRESH_PRODUCT_CREATED);
            }
            if (existingInventory.getCleanStatus() != null && existingInventory.getCleanStatus() == 1) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_CLEANING);
            }
        }
        try {
            pythonSourceNotifier.addInventory(detailId, inventoryUser, tenantId);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            if (message != null && message.contains("该商品已入库")) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ALREADY_EXISTS);
            }
            if (message != null && message.contains("详情记录不存在")) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_DETAIL_NOT_EXISTS);
            }
            if (message != null && message.contains("清洗中")) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_CLEANING);
            }
            if (message != null && message.contains("创建的商品无法刷新")) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_REFRESH_PRODUCT_CREATED);
            }
            log.error("[addFromSourceDetail][detailId={} 入库适配器调用失败]", detailId, e);
            throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ADAPTER_FAILED);
        }
        try {
            Long inventoryId = existingInventory != null ? existingInventory.getId() : findSourceInventoryId(detail);
            if (inventoryId == null) {
                throw new IllegalStateException("Python 入库成功后未找到货源记录");
            }
            normalizeSourceInventoryImages(inventoryId);
        } catch (RuntimeException e) {
            log.error("[addFromSourceDetail][detailId={} 入库后图片转 JPEG 失败]", detailId, e);
            throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ADAPTER_FAILED);
        }
    }

    @Override
    public SourceInventoryManualImportRespVO manualImportBatch(List<String> detailUrls, String inventoryUser, Long tenantId) {
        List<String> normalizedUrls = normalizeDetailUrls(detailUrls);
        List<SourceInventoryManualImportRespVO.ItemResult> results = new ArrayList<>(normalizedUrls.size());
        int successCount = 0;
        for (String detailUrl : normalizedUrls) {
            try {
                manualImportOne(detailUrl, inventoryUser, tenantId);
                results.add(new SourceInventoryManualImportRespVO.ItemResult(detailUrl, true, "入库成功"));
                successCount++;
            } catch (RuntimeException e) {
                results.add(new SourceInventoryManualImportRespVO.ItemResult(detailUrl, false, resolveManualImportFailureMessage(e)));
            }
        }
        return new SourceInventoryManualImportRespVO(
                normalizedUrls.size(),
                successCount,
                normalizedUrls.size() - successCount,
                results
        );
    }

    @Override
    public void refreshInventory(Long id, String inventoryUser, Long tenantId) {
        SourceInventoryDO inventory = validateExistsInternal(id);
        // 前端提示不能替代接口约束，避免直接调用接口改写已创建商品的货源数据。
        if (inventory.getXianyuProductId() != null
                || (inventory.getInventoryStatus() != null
                && inventory.getInventoryStatus() == SourceInventoryStatusEnum.PRODUCT_CREATED.getStatus())) {
            throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_REFRESH_PRODUCT_CREATED);
        }
        try {
            pythonSourceNotifier.refreshInventory(id, inventoryUser, tenantId);
            normalizeSourceInventoryImages(id);
        } catch (RuntimeException e) {
            if (StrUtil.contains(e.getMessage(), "清洗中")) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_CLEANING);
            }
            log.error("[refreshInventory][id={} 刷新失败]", id, e);
            throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ADAPTER_FAILED);
        }
    }

    @Override
    public PageResult<SourceInventoryRespVO> getInventoryPage(SourceInventoryPageReqVO reqVO) {
        PageResult<SourceInventoryDO> page = sourceInventoryMapper.selectPage(reqVO);
        PageResult<SourceInventoryRespVO> result = BeanUtils.toBean(page, SourceInventoryRespVO.class);
        List<SourceInventoryDO> records = page.getList();
        List<SourceInventoryRespVO> items = result.getList();
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setHasCleanHtml(StrUtil.isNotBlank(records.get(i).getCleanHtml()));
        }
        return result;
    }

    @Override
    public void deleteInventory(Long id) {
        validateExistsInternal(id);
        sourceInventoryMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(List<Long> ids) {
        for (Long id : ids) {
            deleteInventory(id);
        }
    }

    @Override
    public void exportCsv(SourceInventoryPageReqVO reqVO, HttpServletResponse response) {
        List<SourceInventoryDO> list = sourceInventoryMapper.selectList(reqVO);
        String fileName = URLEncoder.encode("货源库.csv", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
        try {
            response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            PrintWriter writer = new PrintWriter(response.getOutputStream(), false, StandardCharsets.UTF_8);
            writeCsvRow(writer,
                    "ID(id)", "采集任务ID(task_id)", "搜索结果ID(source_item_id)", "详情ID(source_detail_id)",
                    "外部商品ID(external_item_id)", "商品名称(title)", "商品描述(description)",
                    "搜索关键词(search_keyword)", "售价(元)(price)", "原价(元)(original_price)",
                    "建议售价(元)(suggested_price)", "利润率(%)(profit_margin)", "成色(item_condition)",
                    "功能状态(function_status)", "品牌(brand)", "主图URL(pic_url)", "详情页URL(detail_url)",
                    "商品图片列表(images)", "库存(stock)", "发货地(location)", "省份(province)", "城市(city)",
                    "运费(transport_fee)", "浏览量(view_count)", "想要量(want_count)", "已售数量(sold_count)",
                    "卖家昵称(seller_name)", "卖家城市(seller_city)", "卖家信用等级(seller_credit)",
                    "卖家级别(seller_level)", "分类名称(category_name)", "商品类型(item_type)", "标签列表(tag_list)",
                    "入库状态(inventory_status)", "入库时间(inventory_time)", "入库操作人(inventory_user)",
                    "闲鱼商品ID(xianyu_product_id)", "闲鱼账号ID(xianyu_account_id)",
                    "闲管家远端ID(xianyu_remote_id)", "闲鱼状态(xianyu_status)", "备注(remark)",
                    "审核备注(audit_remark)", "创建者(creator)", "创建时间(create_time)", "更新者(updater)",
                    "更新时间(update_time)", "是否删除(deleted)", "租户ID(tenant_id)");
            for (SourceInventoryDO item : list) {
                writeCsvRow(writer,
                        item.getId(), item.getTaskId(), item.getSourceItemId(), item.getSourceDetailId(),
                        item.getExternalItemId(), item.getTitle(), item.getDescription(), item.getSearchKeyword(),
                        formatYuan(item.getPrice()), formatYuan(item.getOriginalPrice()), formatYuan(item.getSuggestedPrice()),
                        item.getProfitMargin(), item.getItemCondition(), item.getFunctionStatus(), item.getBrand(),
                        item.getPicUrl(), item.getDetailUrl(), item.getImages(), item.getStock(), item.getLocation(),
                        item.getProvince(), item.getCity(), item.getTransportFee(), item.getViewCount(), item.getWantCount(),
                        item.getSoldCount(), item.getSellerName(), item.getSellerCity(), item.getSellerCredit(),
                        item.getSellerLevel(), item.getCategoryName(), item.getItemType(), item.getTagList(),
                        item.getInventoryStatus(), formatDateTime(item.getInventoryTime()), item.getInventoryUser(),
                        item.getXianyuProductId(), item.getXianyuAccountId(), item.getXianyuRemoteId(), item.getXianyuStatus(),
                        item.getRemark(), item.getAuditRemark(), item.getCreator(), formatDateTime(item.getCreateTime()),
                        item.getUpdater(), formatDateTime(item.getUpdateTime()), item.getDeleted(), item.getTenantId());
            }
            writer.flush();
        } catch (IOException e) {
            log.error("[exportCsv][货源库导出失败]", e);
            throw new RuntimeException("货源库导出失败", e);
        }
    }

    private void writeCsvRow(PrintWriter writer, Object... values) {
        StringJoiner row = new StringJoiner(",");
        for (Object value : values) {
            row.add(csv(value));
        }
        writer.println(row);
    }

    private String formatYuan(Long value) {
        return value == null ? "" : String.format("%.2f", value / 100.0);
    }

    private String formatDateTime(java.time.LocalDateTime value) {
        return value == null ? "" : value.format(DATE_FORMATTER);
    }

    private SourceInventoryDO validateExistsInternal(Long id) {
        SourceInventoryDO inventory = sourceInventoryMapper.selectById(id);
        if (inventory == null) {
            throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_NOT_EXISTS);
        }
        return inventory;
    }

    private String csv(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private void manualImportOne(String detailUrl, String inventoryUser, Long tenantId) {
        try {
            pythonSourceNotifier.manualImport(detailUrl, inventoryUser, tenantId);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            if (message != null && (message.contains("商品已入库") || message.contains("该链接已入库"))) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ALREADY_EXISTS);
            }
            if (message != null && (message.contains("链接") || message.contains("商品ID"))) {
                throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_INVALID_LINK);
            }
            log.error("[manualImportOne][detailUrl={} 人工入库调用失败]", detailUrl, e);
            throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ADAPTER_FAILED);
        }
    }

    private Long findSourceInventoryId(SourceItemDetailDO detail) {
        if (detail == null) {
            return null;
        }
        List<SourceInventoryDO> inventories = StrUtil.isNotBlank(detail.getExternalItemId())
                ? sourceInventoryMapper.selectListByExternalItemIds(List.of(detail.getExternalItemId()))
                : sourceInventoryMapper.selectListBySourceDetailIds(List.of(detail.getId()));
        return inventories.stream().max(Comparator.comparing(SourceInventoryDO::getId))
                .map(SourceInventoryDO::getId).orElse(null);
    }

    private void normalizeSourceInventoryImages(Long inventoryId) {
        SourceInventoryDO inventory = validateExistsInternal(inventoryId);
        List<String> images = StrUtil.isBlank(inventory.getImages())
                ? List.of() : JsonUtils.parseArray(inventory.getImages(), String.class);
        Map<String, String> storedUrls = new HashMap<>();
        for (String image : images) {
            if (StrUtil.isNotBlank(image)) {
                storedUrls.computeIfAbsent(image, sourceImageStore::storeAsJpeg);
            }
        }
        String picUrl = StrUtil.isBlank(inventory.getPicUrl()) ? inventory.getPicUrl()
                : storedUrls.computeIfAbsent(inventory.getPicUrl(), sourceImageStore::storeAsJpeg);
        List<String> jpegImages = images.stream()
                .map(image -> StrUtil.isBlank(image) ? image : storedUrls.get(image))
                .toList();
        SourceInventoryDO update = new SourceInventoryDO();
        update.setId(inventoryId);
        update.setPicUrl(picUrl);
        if (!images.isEmpty()) {
            update.setImages(JsonUtils.toJsonString(jpegImages));
        }
        sourceInventoryMapper.updateById(update);
    }

    private List<String> normalizeDetailUrls(List<String> detailUrls) {
        Set<String> normalized = new LinkedHashSet<>();
        if (detailUrls != null) {
            for (String detailUrl : detailUrls) {
                String trimmed = StrUtil.trim(detailUrl);
                if (StrUtil.isNotBlank(trimmed)) {
                    normalized.add(trimmed);
                }
            }
        }
        if (normalized.isEmpty()) {
            throw exception(SourceTaskErrorCodeConstants.SOURCE_INVENTORY_INVALID_LINK);
        }
        return new ArrayList<>(normalized);
    }

    private String resolveManualImportFailureMessage(RuntimeException e) {
        String message = e.getMessage();
        if (StrUtil.isNotBlank(message)) {
            return message;
        }
        return SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ADAPTER_FAILED.getMsg();
    }

    @Override
    public SourceInventoryCleanSubmitRespVO submitCleanTask(SourceInventoryCleanSubmitReqVO reqVO) {
        return sourceCleanService.submit(reqVO);
    }

    @Override
    public SourceInventoryCleanRecommendRespVO recommendCleanSkills(List<Long> inventoryIds) {
        return sourceCleanService.recommend(inventoryIds);
    }

    @Override
    public SourceInventoryCleanStatusRespVO getCleanTaskStatus(Long taskId) {
        return sourceCleanService.status(taskId);
    }

    @Override
    public List<SourceCleanSkillRespVO> getCleanSkillOptions() {
        return sourceCleanService.skillOptions();
    }
}
