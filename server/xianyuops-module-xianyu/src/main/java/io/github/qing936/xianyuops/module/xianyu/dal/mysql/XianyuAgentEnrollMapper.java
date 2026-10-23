package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentEnrollDO;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEnrollStatusEnum;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface XianyuAgentEnrollMapper extends BaseMapperX<XianyuAgentEnrollDO> {

    /**
     * Filter 调用：按注册码哈希查「可用」注册码（未使用 + 未过期）。
     * uk_code_hash 保证租户内至多一行；命中即放行首次注册心跳。
     */
    default XianyuAgentEnrollDO selectUsableByCodeHash(String codeHash, LocalDateTime now) {
        LambdaQueryWrapperX<XianyuAgentEnrollDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(XianyuAgentEnrollDO::getCodeHash, codeHash);
        wrapper.isNull(XianyuAgentEnrollDO::getUsedTime);
        wrapper.gt(XianyuAgentEnrollDO::getExpireTime, now);
        return selectOne(wrapper);
    }

    /**
     * 分页：状态为派生值（见 {@link XianyuAgentEnrollStatusEnum}），按 used_time / expire_time 翻译成 SQL 条件。
     */
    default PageResult<XianyuAgentEnrollDO> selectPage(XianyuAgentEnrollPageReqVO reqVO, LocalDateTime now) {
        LambdaQueryWrapperX<XianyuAgentEnrollDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.likeIfPresent(XianyuAgentEnrollDO::getRemark, reqVO.getRemark());
        XianyuAgentEnrollStatusEnum status = resolveStatus(reqVO.getStatus());
        if (status == XianyuAgentEnrollStatusEnum.UNUSED) {
            wrapper.isNull(XianyuAgentEnrollDO::getUsedTime);
            wrapper.gt(XianyuAgentEnrollDO::getExpireTime, now);
        } else if (status == XianyuAgentEnrollStatusEnum.USED) {
            wrapper.isNotNull(XianyuAgentEnrollDO::getUsedTime);
        } else if (status == XianyuAgentEnrollStatusEnum.EXPIRED) {
            wrapper.isNull(XianyuAgentEnrollDO::getUsedTime);
            wrapper.le(XianyuAgentEnrollDO::getExpireTime, now);
        }
        wrapper.orderByDesc(XianyuAgentEnrollDO::getId);
        return selectPage(reqVO, wrapper);
    }

    private static XianyuAgentEnrollStatusEnum resolveStatus(Integer status) {
        if (status == null) {
            return null;
        }
        for (XianyuAgentEnrollStatusEnum e : XianyuAgentEnrollStatusEnum.values()) {
            if (e.getStatus().equals(status)) {
                return e;
            }
        }
        return null;
    }

}
