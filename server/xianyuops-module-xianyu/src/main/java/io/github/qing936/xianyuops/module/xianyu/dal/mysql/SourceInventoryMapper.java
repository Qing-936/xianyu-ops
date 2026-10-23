package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import cn.hutool.core.collection.CollUtil;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceInventoryDO;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceInventoryStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.Collections;

@Mapper
public interface SourceInventoryMapper extends BaseMapperX<SourceInventoryDO> {

    default int clearCreatedProduct(Long productId) {
        return update(null, new LambdaUpdateWrapper<SourceInventoryDO>()
            .set(SourceInventoryDO::getXianyuProductId, null)
            .set(SourceInventoryDO::getXianyuAccountId, null)
            .set(SourceInventoryDO::getInventoryStatus, SourceInventoryStatusEnum.IN_STOCK.getStatus())
            .eq(SourceInventoryDO::getXianyuProductId, productId));
    }

    default int clearCreatedProductBySourceInventoryId(Long sourceInventoryId) {
        return update(null, new LambdaUpdateWrapper<SourceInventoryDO>()
            .set(SourceInventoryDO::getXianyuProductId, null)
            .set(SourceInventoryDO::getXianyuAccountId, null)
            .set(SourceInventoryDO::getInventoryStatus, SourceInventoryStatusEnum.IN_STOCK.getStatus())
            .eq(SourceInventoryDO::getId, sourceInventoryId));
    }

    @Select("SELECT * FROM app_source_inventory WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    SourceInventoryDO selectByIdForUpdate(@Param("id") Long id);

    default PageResult<SourceInventoryDO> selectPage(SourceInventoryPageReqVO reqVO) {
        return selectPage(reqVO, buildQuery(reqVO));
    }

    default java.util.List<SourceInventoryDO> selectList(SourceInventoryPageReqVO reqVO) {
        return selectList(buildQuery(reqVO));
    }

    default java.util.List<SourceInventoryDO> selectListBySourceDetailIds(java.util.List<Long> sourceDetailIds) {
        return selectList(SourceInventoryDO::getSourceDetailId, sourceDetailIds);
    }

    default java.util.List<SourceInventoryDO> selectListByExternalItemIds(Collection<String> externalItemIds) {
        if (CollUtil.isEmpty(externalItemIds)) {
            return Collections.emptyList();
        }
        return selectList(SourceInventoryDO::getExternalItemId, externalItemIds);
    }

    default java.util.List<SourceInventoryDO> selectListByDetailUrls(Collection<String> detailUrls) {
        if (CollUtil.isEmpty(detailUrls)) {
            return Collections.emptyList();
        }
        return selectList(SourceInventoryDO::getDetailUrl, detailUrls);
    }

    private static LambdaQueryWrapperX<SourceInventoryDO> buildQuery(SourceInventoryPageReqVO reqVO) {
        LambdaQueryWrapperX<SourceInventoryDO> query = new LambdaQueryWrapperX<SourceInventoryDO>()
                .likeIfPresent(SourceInventoryDO::getSearchKeyword, reqVO.getSearchKeyword())
                .likeIfPresent(SourceInventoryDO::getTitle, reqVO.getTitle())
                .eqIfPresent(SourceInventoryDO::getInventoryStatus, reqVO.getInventoryStatus())
                .geIfPresent(SourceInventoryDO::getInventoryTime, reqVO.getInventoryTimeStart())
                .leIfPresent(SourceInventoryDO::getInventoryTime, reqVO.getInventoryTimeEnd())
                .orderByDesc(SourceInventoryDO::getInventoryTime)
                .orderByDesc(SourceInventoryDO::getId);
        query.apply(reqVO.getDisplayStatus() != null,
                "(CASE WHEN clean_status = 3 THEN 8 WHEN clean_status = 1 THEN 6 " +
                    "WHEN xianyu_product_id IS NOT NULL THEN 2 WHEN inventory_status >= 2 THEN inventory_status " +
                    "WHEN clean_status = 2 THEN 7 ELSE inventory_status END) = {0}", reqVO.getDisplayStatus());
        return query;
    }

}
