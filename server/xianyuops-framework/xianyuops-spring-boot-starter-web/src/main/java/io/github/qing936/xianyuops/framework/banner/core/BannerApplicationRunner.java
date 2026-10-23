package io.github.qing936.xianyuops.framework.banner.core;

import cn.hutool.core.thread.ThreadUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.util.ClassUtils;

import java.util.concurrent.TimeUnit;

/**
 * 项目启动成功后，提供文档相关的地址
 *
 * @author XianyuOps系统
 */
@Slf4j
public class BannerApplicationRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        ThreadUtil.execute(() -> {
            ThreadUtil.sleep(1, TimeUnit.SECONDS); // 延迟 1 秒，保证输出到结尾
            log.info("\n----------------------------------------------------------\n\t" +
                            "项目启动成功！\n\t" +
                            "接口文档: \t{} \n\t" +


                            "----------------------------------------------------------",
                    "http://localhost:48180/doc.html");



            // 支付平台
            if (isNotPresent("io.github.qing936.xianyuops.module.pay.framework.pay.config.PayConfiguration")) {
                System.out.println("[支付系统 xianyuops-module-pay - 已禁用][参考 https://doc.localhost.cn/pay/build/ 开启]");
            }
            // AI 大模型
            if (isNotPresent("io.github.qing936.xianyuops.module.ai.framework.web.config.AiWebConfiguration")) {
                System.out.println("[AI 大模型 xianyuops-module-ai - 已禁用][参考 https://doc.localhost.cn/ai/build/ 开启]");
            }

        });
    }

    private static boolean isNotPresent(String className) {
        return !ClassUtils.isPresent(className, ClassUtils.getDefaultClassLoader());
    }

}
