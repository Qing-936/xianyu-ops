package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceItemDetailDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SourceItemDetailMapper extends BaseMapperX<SourceItemDetailDO> {

    default java.util.List<SourceItemDetailDO> selectListByItemIds(java.util.List<Long> itemIds) {
        return selectList(SourceItemDetailDO::getItemId, itemIds);
    }

}
