package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class SourceCleanWorker {
    private final SourceCleanService service;

    @Bean("sourceCleanScheduler")
    public ThreadPoolTaskScheduler sourceCleanScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("source-clean-");
        return scheduler;
    }

    // 清洗调用本身耗时较长，空闲时无需每 10 秒查询一次队列表
    @Scheduled(fixedDelay = 10000, initialDelay = 15000, scheduler = "sourceCleanScheduler")
    public void poll() {
        try {
            service.poll();
        } catch (Exception e) {
            log.error("[sourceClean][队列执行异常 type={}, message={}]",
                    e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }
}
