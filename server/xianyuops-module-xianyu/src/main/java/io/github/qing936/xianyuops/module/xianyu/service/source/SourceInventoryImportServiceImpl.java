package io.github.qing936.xianyuops.module.xianyu.service.source;

import cn.hutool.core.util.StrUtil;
import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportItemReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportParseRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportResultRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceInventoryDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceInventoryMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskErrorCodeConstants;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceInventoryStatusEnum;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceInventoryExcelParser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 货源库批量导入 Service 实现
 *
 * <p>流程：解析 Excel（表头与商品批量导入一致）生成预览 → 前端确认后逐条直接写入货源库，
 * 不经过 Python 抓取；图片保留原始外链。</p>
 */
@Service
@Slf4j
public class SourceInventoryImportServiceImpl implements SourceInventoryImportService {

    /** 货源库库存默认值（Excel 无库存列） */
    private static final int DEFAULT_STOCK = 1;

    /** 价格区间解析：如 0.30-128.50 */
    private static final Pattern PRICE_RANGE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)");

    /** 闲鱼商品 ID：链接参数 id=xxx */
    private static final Pattern XIANYU_ITEM_ID_PATTERN = Pattern.compile("[?&]id=(\\d+)");

    /** 闲鱼商品 ID：/item/xxx 路径 */
    private static final Pattern XIANYU_ITEM_PATH_PATTERN = Pattern.compile("/item/(\\d+)");

    /** 1688 商品 ID：/offer/xxx.html 路径 */
    private static final Pattern OFFER_ID_PATTERN = Pattern.compile("/offer/(\\d+)");

    @Resource
    private SourceInventoryMapper sourceInventoryMapper;

    // ========== 1. 解析 Excel ==========

    @Override
    public List<SourceInventoryImportParseRespVO> parse(MultipartFile file) {
        List<List<String>> rows;
        try {
            rows = SourceInventoryExcelParser.parse(file);
        } catch (Exception e) {
            throw new IllegalArgumentException(StrUtil.cleanBlank(e.getMessage()));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Excel 文件为空");
        }
        // 1. 定位表头行（包含「标题」与「商品链接」列）
        int headerRow = -1;
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row != null && containsColumn(row, "标题") && containsColumn(row, "商品链接")) {
                headerRow = i;
                break;
            }
        }
        if (headerRow < 0) {
            throw new IllegalArgumentException("未找到表头行（需包含「标题」「商品链接」列）");
        }
        // 2. 构建列名 → 索引映射
        Map<String, Integer> colMap = buildColumnMap(rows.get(headerRow));
        int idxTitle = colMap.getOrDefault("标题", -1);
        int idxPlatform = colMap.getOrDefault("平台", -1);
        int idxPrice = colMap.getOrDefault("价格", -1);
        int idxImages = colMap.getOrDefault("图片", -1);
        int idxLink = colMap.getOrDefault("商品链接", -1);
        int idxSeller = colMap.getOrDefault("卖家", -1);
        int idxCondition = colMap.getOrDefault("成色", -1);
        int idxFunction = colMap.getOrDefault("功能状态", -1);
        // 3. 逐行解析
        List<SourceInventoryImportParseRespVO> result = new ArrayList<>();
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row == null || row.isEmpty()) {
                continue;
            }
            String title = cell(row, idxTitle);
            if (StrUtil.isBlank(title)) {
                continue;
            }
            SourceInventoryImportParseRespVO vo = new SourceInventoryImportParseRespVO();
            vo.setRowNo(i + 1);
            vo.setTitle(title.trim());
            vo.setPlatform(StrUtil.cleanBlank(cell(row, idxPlatform)));
            vo.setPriceText(StrUtil.cleanBlank(cell(row, idxPrice)));
            vo.setLink(StrUtil.cleanBlank(cell(row, idxLink)));
            vo.setSeller(StrUtil.cleanBlank(cell(row, idxSeller)));
            vo.setItemCondition(StrUtil.cleanBlank(cell(row, idxCondition)));
            vo.setFunctionStatus(StrUtil.cleanBlank(cell(row, idxFunction)));
            vo.setWarnings(new ArrayList<>());
            // 图片：分号/换行分隔，仅保留 http 外链
            List<String> images = parseImages(cell(row, idxImages));
            if (images.isEmpty()) {
                vo.getWarnings().add("未解析到图片");
            }
            vo.setImages(images);
            // 价格：区间价取最小值 → 分
            Long price = parsePrice(vo.getPriceText());
            vo.setPrice(price);
            if (price == null) {
                vo.getWarnings().add("无法解析价格：" + vo.getPriceText());
            }
            // 外部商品 ID：从链接提取，用于去重
            if (StrUtil.isBlank(vo.getLink())) {
                vo.getWarnings().add("商品链接为空，无法去重");
            } else {
                vo.setExternalItemId(extractExternalItemId(vo.getLink()));
                if (StrUtil.isBlank(vo.getExternalItemId())) {
                    vo.getWarnings().add("链接无法解析商品ID，无法去重");
                }
            }
            result.add(vo);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("未解析到任何商品数据（表头下行无数据）");
        }
        // 4. 标记已入库（按 externalItemId 或 detailUrl 去重）
        markDuplicated(result);
        return result;
    }

    private boolean containsColumn(List<String> row, String name) {
        for (String cell : row) {
            if (StrUtil.isNotBlank(cell) && cell.trim().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Integer> buildColumnMap(List<String> header) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i);
            if (StrUtil.isBlank(cell)) {
                continue;
            }
            String name = cell.trim();
            map.putIfAbsent(name, i);
            // 兼容带单位的表头（如「价格(元)」）
            if (name.contains("价格")) {
                map.putIfAbsent("价格", i);
            } else if (name.contains("图片")) {
                map.putIfAbsent("图片", i);
            } else if (name.contains("标题")) {
                map.putIfAbsent("标题", i);
            } else if (name.contains("链接")) {
                map.putIfAbsent("商品链接", i);
            } else if (name.contains("平台")) {
                map.putIfAbsent("平台", i);
            } else if (name.contains("卖家")) {
                map.putIfAbsent("卖家", i);
            } else if (name.contains("功能状态")) {
                map.putIfAbsent("功能状态", i);
            } else if (name.contains("成色")) {
                map.putIfAbsent("成色", i);
            }
        }
        return map;
    }

    private String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        return value == null ? null : value.trim();
    }

    // ========== 2. 数据提取 ==========

    /** 图片列：分号（中英文）、换行分隔，仅保留 http 外链 */
    private List<String> parseImages(String imagesText) {
        List<String> result = new ArrayList<>();
        if (StrUtil.isBlank(imagesText)) {
            return result;
        }
        for (String part : imagesText.split("[;；\\n\\r]+")) {
            String url = part.trim();
            if (StrUtil.isBlank(url) || !url.startsWith("http")) {
                continue;
            }
            result.add(url);
        }
        return result;
    }

    /** 价格文本 → 分；区间价取最小值 */
    private Long parsePrice(String priceText) {
        if (StrUtil.isBlank(priceText)) {
            return null;
        }
        String text = priceText.trim();
        Matcher range = PRICE_RANGE_PATTERN.matcher(text);
        if (range.find()) {
            return yuanToFen(range.group(1));
        }
        return yuanToFen(text);
    }

    /** 元（字符串）→ 分 */
    private Long yuanToFen(String yuan) {
        try {
            BigDecimal value = new BigDecimal(yuan.trim());
            return value.multiply(BigDecimal.valueOf(100)).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从商品链接提取外部商品 ID：闲鱼 id=/item/、1688 offer */
    private String extractExternalItemId(String link) {
        for (Pattern pattern : List.of(XIANYU_ITEM_ID_PATTERN, XIANYU_ITEM_PATH_PATTERN, OFFER_ID_PATTERN)) {
            Matcher matcher = pattern.matcher(link);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private void markDuplicated(List<SourceInventoryImportParseRespVO> rows) {
        Set<String> existedExternalIds = new HashSet<>();
        Set<String> existedDetailUrls = new HashSet<>();
        List<String> externalIds = rows.stream()
                .map(SourceInventoryImportParseRespVO::getExternalItemId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        List<String> links = rows.stream()
                .map(SourceInventoryImportParseRespVO::getLink)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (!externalIds.isEmpty()) {
            sourceInventoryMapper.selectListByExternalItemIds(externalIds)
                    .forEach(item -> existedExternalIds.add(item.getExternalItemId()));
        }
        if (!links.isEmpty()) {
            sourceInventoryMapper.selectListByDetailUrls(links)
                    .forEach(item -> existedDetailUrls.add(item.getDetailUrl()));
        }
        for (SourceInventoryImportParseRespVO row : rows) {
            boolean duplicated = (StrUtil.isNotBlank(row.getExternalItemId())
                    && existedExternalIds.contains(row.getExternalItemId()))
                    || existedDetailUrls.contains(row.getLink());
            row.setDuplicated(duplicated);
            if (duplicated) {
                row.getWarnings().add("该商品已入库，导入时将跳过");
            }
        }
    }

    // ========== 3. 批量导入落库 ==========

    @Override
    public List<SourceInventoryImportResultRespVO> create(SourceInventoryImportCreateReqVO reqVO, String inventoryUser) {
        List<SourceInventoryImportResultRespVO> results = new ArrayList<>(reqVO.getItems().size());
        for (SourceInventoryImportItemReqVO item : reqVO.getItems()) {
            SourceInventoryImportResultRespVO result = new SourceInventoryImportResultRespVO();
            result.setRowNo(item.getRowNo());
            result.setTitle(item.getTitle());
            try {
                validateNotDuplicated(item.getExternalItemId(), item.getLink());
                SourceInventoryDO entity = buildInventory(item, inventoryUser);
                sourceInventoryMapper.insert(entity);
                result.setSuccess(true);
                result.setInventoryId(entity.getId());
                log.info("[create][成功] rowNo={} title={} inventoryId={}", item.getRowNo(), item.getTitle(), entity.getId());
            } catch (Exception e) {
                result.setSuccess(false);
                result.setMessage(StrUtil.cleanBlank(e.getMessage()));
                log.warn("[create][失败] rowNo={} title={}", item.getRowNo(), item.getTitle(), e);
            }
            results.add(result);
        }
        return results;
    }

    /** 去重：externalItemId 或 detailUrl 任一命中既有记录即视为已入库 */
    private void validateNotDuplicated(String externalItemId, String detailUrl) {
        String alreadyExistsMessage = SourceTaskErrorCodeConstants.SOURCE_INVENTORY_ALREADY_EXISTS.getMsg();
        if (StrUtil.isNotBlank(externalItemId)
                && sourceInventoryMapper.selectCount(SourceInventoryDO::getExternalItemId, externalItemId) > 0) {
            throw new IllegalArgumentException(alreadyExistsMessage);
        }
        if (sourceInventoryMapper.selectCount(SourceInventoryDO::getDetailUrl, detailUrl) > 0) {
            throw new IllegalArgumentException(alreadyExistsMessage);
        }
    }

    private SourceInventoryDO buildInventory(SourceInventoryImportItemReqVO item, String inventoryUser) {
        List<String> images = item.getImages() == null ? List.<String>of()
                : item.getImages().stream().filter(StrUtil::isNotBlank).toList();
        SourceInventoryDO entity = new SourceInventoryDO();
        entity.setExternalItemId(item.getExternalItemId());
        entity.setTitle(item.getTitle());
        entity.setPrice(priceToFen(item.getPrice()));
        entity.setPicUrl(images.isEmpty() ? null : images.get(0));
        entity.setImages(images.isEmpty() ? null : JsonUtils.toJsonString(images));
        entity.setDetailUrl(item.getLink());
        entity.setSellerName(item.getSeller());
        entity.setItemCondition(item.getItemCondition());
        entity.setFunctionStatus(item.getFunctionStatus());
        entity.setStock(DEFAULT_STOCK);
        entity.setInventoryStatus(SourceInventoryStatusEnum.IN_STOCK.getStatus());
        entity.setInventoryTime(LocalDateTime.now());
        entity.setInventoryUser(inventoryUser);
        return entity;
    }

    private Long priceToFen(Long price) {
        if (price == null || price < 0) {
            return 0L;
        }
        return price;
    }

}
