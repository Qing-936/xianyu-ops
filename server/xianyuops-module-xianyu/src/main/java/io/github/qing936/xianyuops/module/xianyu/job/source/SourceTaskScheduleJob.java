package io.github.qing936.xianyuops.module.xianyu.job.source;

import io.github.qing936.xianyuops.framework.quartz.core.handler.JobHandler;
import io.github.qing936.xianyuops.framework.tenant.core.job.TenantJob;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceTaskDO;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceTaskMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskStatusEnum.RUNNING;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskTriggerModeEnum.DAILY;

/**
 * 货源采集定时任务扫描器（每分钟执行一次）
 * <p>
 * 扫描 trigger_mode=1（每日定时）且 status=0（运行中）的任务，
 * 若 schedule_time 匹配当前时间（时+分）且当天尚未执行过，则提交给 Python 执行。
 */
@Component
@Slf4j
public class SourceTaskScheduleJob implements JobHandler {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Resource
    private SourceTaskMapper sourceTaskMapper;

    @Resource
    private SourceTaskService sourceTaskService;

    @Override
    @TenantJob // 逐租户执行：租户上下文内 SELECT 自动过滤、INSERT 自动填充 tenant_id
    public String execute(String param) {
        LocalTime now = LocalTime.now();
        String currentTime = now.format(HH_MM);
        LocalDate today = LocalDate.now();

        // 查询所有运行中的定时任务
        List<SourceTaskDO> tasks = sourceTaskMapper.selectList(
                new LambdaQueryWrapper<SourceTaskDO>()
                        .eq(SourceTaskDO::getTriggerMode, DAILY.getMode())
                        .eq(SourceTaskDO::getStatus, RUNNING.getStatus()));

        int executed = 0;
        for (SourceTaskDO task : tasks) {
            if (!isMatchTime(task.getScheduleTime(), currentTime)) {
                continue;
            }
            // 检查当天是否已执行过
            if (task.getLastExecutedAt() != null
                    && task.getLastExecutedAt().toLocalDate().isEqual(today)) {
                continue;
            }
            try {
                sourceTaskService.executeScheduledTask(task.getId());
                executed++;
                log.info("[execute][taskId={} scheduleTime={} 已提交执行]", task.getId(), task.getScheduleTime());
            } catch (Exception e) {
                log.warn("[execute][taskId={} 提交执行失败]", task.getId(), e);
            }
        }
        return String.format("定时扫描完成：匹配=%d 已执行=%d", tasks.size(), executed);
    }

    /**
     * 判断任务的 scheduleTime（HH:mm）是否匹配当前时间（HH:mm）
     */
    private boolean isMatchTime(String scheduleTime, String currentTime) {
        if (scheduleTime == null || scheduleTime.isBlank()) {
            return false;
        }
        return scheduleTime.equals(currentTime);
    }
}
