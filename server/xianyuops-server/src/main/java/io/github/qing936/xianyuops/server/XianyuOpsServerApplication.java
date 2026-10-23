package io.github.qing936.xianyuops.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 项目的启动类
 *
 * 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
 * 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
 * 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
 *
 * @author XianyuOps系统
 */
@SuppressWarnings("SpringComponentScan") // 忽略 IDEA 无法识别 ${xianyuops.info.base-package}
@SpringBootApplication(scanBasePackages = {"${xianyuops.info.base-package}.server", "${xianyuops.info.base-package}.module"})
public class XianyuOpsServerApplication {

    public static void main(String[] args) {
        // 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章

        SpringApplication.run(XianyuOpsServerApplication.class, args);
//        new SpringApplicationBuilder(XianyuOpsServerApplication.class)
//                .applicationStartup(new BufferingApplicationStartup(20480))
//                .run(args);

        // 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.localhost.cn/quick-start/ 文章
    }

}
