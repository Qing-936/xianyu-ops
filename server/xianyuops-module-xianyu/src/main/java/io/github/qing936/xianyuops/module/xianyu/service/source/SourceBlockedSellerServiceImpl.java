package io.github.qing936.xianyuops.module.xianyu.service.source;

import cn.hutool.core.util.StrUtil;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.common.util.object.BeanUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerSaveReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceBlockedSellerDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceItemDetailDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceBlockedSellerMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceItemDetailMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceBlockedSellerErrorCodeConstants.*;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceBlockedSellerStatusEnum.EFFECTIVE;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceBlockedSellerStatusEnum.RELEASED;

/**
 * 货源拉黑卖家 Service 实现
 */
@Service
@Slf4j
@Validated
public class SourceBlockedSellerServiceImpl implements SourceBlockedSellerService {

    @Resource
    private SourceBlockedSellerMapper sourceBlockedSellerMapper;

    @Resource
    private SourceItemDetailMapper sourceItemDetailMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createBlockedSeller(SourceBlockedSellerSaveReqVO reqVO) {
        String sellerId = StrUtil.trimToNull(reqVO.getSellerId());
        String sellerName = StrUtil.trimToNull(reqVO.getSellerName());
        // 1. 携带 detailId 时，从采集详情补全 sellerId / sellerName
        if (reqVO.getDetailId() != null) {
            SourceItemDetailDO detail = sourceItemDetailMapper.selectById(reqVO.getDetailId());
            if (detail != null) {
                if (sellerId == null) {
                    sellerId = StrUtil.trimToNull(detail.getSellerId());
                }
                if (sellerName == null) {
                    sellerName = StrUtil.trimToNull(detail.getSellerName());
                }
            }
        }
        // 2. 校验
        if (sellerId == null && sellerName == null) {
            throw exception(SOURCE_BLOCKED_SELLER_SELLER_REQUIRED);
        }
        if (StrUtil.isBlank(reqVO.getBlockReason())) {
            throw exception(SOURCE_BLOCKED_SELLER_REASON_REQUIRED);
        }
        if (isBlocked(sellerId, sellerName)) {
            throw exception(SOURCE_BLOCKED_SELLER_ALREADY_EXISTS);
        }
        // 3. 入库（seller_id 优先、seller_name 兜底）
        SourceBlockedSellerDO seller = new SourceBlockedSellerDO();
        seller.setSellerId(sellerId);
        seller.setSellerName(sellerName != null ? sellerName : "");
        seller.setBlockReason(reqVO.getBlockReason().trim());
        seller.setSourceTaskId(reqVO.getSourceTaskId());
        seller.setStatus(EFFECTIVE.getStatus());
        seller.setBlockTime(LocalDateTime.now());
        sourceBlockedSellerMapper.insert(seller);
        return seller.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBlockedSeller(SourceBlockedSellerSaveReqVO reqVO) {
        validateBlockedSellerExists(reqVO.getId());
        if (StrUtil.isBlank(reqVO.getBlockReason())) {
            throw exception(SOURCE_BLOCKED_SELLER_REASON_REQUIRED);
        }
        SourceBlockedSellerDO update = new SourceBlockedSellerDO();
        update.setId(reqVO.getId());
        update.setBlockReason(reqVO.getBlockReason().trim());
        sourceBlockedSellerMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseBlockedSeller(Long id) {
        SourceBlockedSellerDO seller = validateBlockedSellerExists(id);
        if (RELEASED.getStatus().equals(seller.getStatus())) {
            throw exception(SOURCE_BLOCKED_SELLER_NOT_EFFECTIVE);
        }
        SourceBlockedSellerDO update = new SourceBlockedSellerDO();
        update.setId(id);
        update.setStatus(RELEASED.getStatus());
        update.setUnblockTime(LocalDateTime.now());
        sourceBlockedSellerMapper.updateById(update);
    }

    @Override
    public SourceBlockedSellerRespVO getBlockedSeller(Long id) {
        return BeanUtils.toBean(validateBlockedSellerExists(id), SourceBlockedSellerRespVO.class);
    }

    @Override
    public PageResult<SourceBlockedSellerRespVO> getBlockedSellerPage(SourceBlockedSellerPageReqVO reqVO) {
        return BeanUtils.toBean(sourceBlockedSellerMapper.selectPage(reqVO), SourceBlockedSellerRespVO.class);
    }

    @Override
    public boolean isBlocked(String sellerId, String sellerName) {
        if (StrUtil.isNotBlank(sellerId)
                && sourceBlockedSellerMapper.selectEffectiveBySellerId(sellerId) != null) {
            return true;
        }
        return StrUtil.isNotBlank(sellerName)
                && sourceBlockedSellerMapper.selectEffectiveBySellerName(sellerName) != null;
    }

    @Override
    public Set<String> getEffectiveSellerIds() {
        return sourceBlockedSellerMapper.selectEffectiveList().stream()
                .map(SourceBlockedSellerDO::getSellerId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getEffectiveSellerNames() {
        return sourceBlockedSellerMapper.selectEffectiveList().stream()
                .map(SourceBlockedSellerDO::getSellerName)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    private SourceBlockedSellerDO validateBlockedSellerExists(Long id) {
        SourceBlockedSellerDO seller = id == null ? null : sourceBlockedSellerMapper.selectById(id);
        if (seller == null) {
            throw exception(SOURCE_BLOCKED_SELLER_NOT_EXISTS);
        }
        return seller;
    }

}
