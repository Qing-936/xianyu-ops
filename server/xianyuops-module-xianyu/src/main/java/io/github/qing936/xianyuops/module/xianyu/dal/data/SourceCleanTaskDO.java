package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_source_clean_task")
public class SourceCleanTaskDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long userId;
    private String requestId;
    private Integer status;
    private Integer totalCount;
    private Integer completedCount;
    private Integer failedCount;
    private String inputs;
    private String results;
    private String errorMessage;
    private LocalDateTime leaseUntil;
}
