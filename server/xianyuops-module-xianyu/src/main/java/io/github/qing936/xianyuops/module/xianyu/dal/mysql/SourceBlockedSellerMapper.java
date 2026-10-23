package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import cn.hutool.core.collection.CollUtil;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceBlockedSellerPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceBlockedSellerDO;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceBlockedSellerStatusEnum;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SourceBlockedSellerMapper extends BaseMapperX<SourceBlockedSellerDO> {

    default PageResult<SourceBlockedSellerDO> selectPage(SourceBlockedSellerPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SourceBlockedSellerDO>()
                .likeIfPresent(SourceBlockedSellerDO::getSellerName, reqVO.getSellerName())
                .eqIfPresent(SourceBlockedSellerDO::getStatus, reqVO.getStatus())
                .eqIfPresent(SourceBlockedSellerDO::getSourceTaskId, reqVO.getSourceTaskId())
                .orderByDesc(SourceBlockedSellerDO::getId));
    }

    /**
     * 查询生效中的黑名单记录（按 seller_id 匹配）
     */
    default SourceBlockedSellerDO selectEffectiveBySellerId(String sellerId) {
        return CollUtil.getFirst(selectList(new LambdaQueryWrapperX<SourceBlockedSellerDO>()
                .eq(SourceBlockedSellerDO::getStatus, SourceBlockedSellerStatusEnum.EFFECTIVE.getStatus())
                .eq(SourceBlockedSellerDO::getSellerId, sellerId)
                .orderByDesc(SourceBlockedSellerDO::getId)));
    }

    /**
     * 查询生效中的黑名单记录（按 seller_name 匹配）
     */
    default SourceBlockedSellerDO selectEffectiveBySellerName(String sellerName) {
        return CollUtil.getFirst(selectList(new LambdaQueryWrapperX<SourceBlockedSellerDO>()
                .eq(SourceBlockedSellerDO::getStatus, SourceBlockedSellerStatusEnum.EFFECTIVE.getStatus())
                .eq(SourceBlockedSellerDO::getSellerName, sellerName)
                .orderByDesc(SourceBlockedSellerDO::getId)));
    }

    /**
     * 查询全部生效中的黑名单记录（用于批量过滤）
     */
    default List<SourceBlockedSellerDO> selectEffectiveList() {
        return selectList(SourceBlockedSellerDO::getStatus, SourceBlockedSellerStatusEnum.EFFECTIVE.getStatus());
    }

}
