package io.github.qing936.xianyuops.module.xianyu.service.source.refine;

/**
 * 货源图片微调服务
 */
public interface SourceImageRefineService {

    /**
     * 按自然语言指令微调货源主图，返回已转存到 OSS 的预览图片地址（不落库）。
     *
     * @param inventoryId 货源编号
     * @param prompt      自然语言微调指令
     * @param userId      操作人编号，用于工作流 user 标识
     * @return 预览图片地址
     */
    String preview(Long inventoryId, String prompt, Long userId);

    /**
     * 将上次生成的预览图应用为该货源主图（替换 picUrl 与 images 首图）。
     *
     * @param inventoryId 货源编号
     */
    void apply(Long inventoryId);
}
