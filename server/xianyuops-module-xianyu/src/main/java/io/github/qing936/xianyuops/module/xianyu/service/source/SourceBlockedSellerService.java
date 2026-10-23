package io.github.qing936.xianyuops.module.xianyu.service.source;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerSaveReqVO;

import java.util.Set;

/**
 * 货源拉黑卖家 Service
 */
public interface SourceBlockedSellerService {

    /**
     * 拉黑卖家
     * 1. sellerId / sellerName 至少一个非空；携带 detailId 时自动补全
     * 2. 生效中的黑名单不允许重复拉黑（sellerId 或 sellerName 命中即视为重复）
     *
     * @return 记录ID
     */
    Long createBlockedSeller(SourceBlockedSellerSaveReqVO reqVO);

    /**
     * 编辑拉黑记录（仅允许修改拉黑原因）
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 记录不存在
     */
    void updateBlockedSeller(SourceBlockedSellerSaveReqVO reqVO);

    /**
     * 解除拉黑（status: 1 → 0），保留历史记录
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 记录不存在或已解除
     */
    void releaseBlockedSeller(Long id);

    /**
     * 查询拉黑记录详情
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 记录不存在
     */
    SourceBlockedSellerRespVO getBlockedSeller(Long id);

    /**
     * 分页查询拉黑记录
     */
    PageResult<SourceBlockedSellerRespVO> getBlockedSellerPage(SourceBlockedSellerPageReqVO reqVO);

    /**
     * 判断卖家是否在生效黑名单中（sellerId 或 sellerName 命中任一即视为已拉黑）
     */
    boolean isBlocked(String sellerId, String sellerName);

    /**
     * 生效中的黑名单卖家ID集合（用于批量过滤）
     */
    Set<String> getEffectiveSellerIds();

    /**
     * 生效中的黑名单卖家名称集合（用于批量过滤）
     */
    Set<String> getEffectiveSellerNames();

}
