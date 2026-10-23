package io.github.qing936.xianyuops.module.xianyu.service.source;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshStatusRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceExecutionRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceItemRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPendingInventoryPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPendingInventoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskRespVO;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * 货源采集任务 Service
 */
public interface SourceTaskService {

    /**
     * 生成共享 Cookie 刷新二维码。
     */
    SourceCookieRefreshRespVO refreshCookieQr();

    /**
     * 查询共享 Cookie 刷新二维码扫码状态。
     */
    SourceCookieRefreshStatusRespVO getCookieRefreshStatus(String sessionId);

    /**
     * 创建采集任务
     * 1. 参数校验（名称长度、fetch_count范围、价格合理性、定时必须有time）
     * 2. 构建DO并入库（status=0, executed_count=0）
     * 3. 一次性任务通知 Python 执行
     *
     * @return 任务ID
     */
    Long createTask(SourceTaskCreateReqVO reqVO);

    /**
     * 分页查询任务列表
     */
    PageResult<SourceTaskRespVO> getTaskPage(SourceTaskPageReqVO reqVO);

    /** 查询尚无任何商品入库的任务及其待入库汇总（只看有数据的最新批次） */
    PageResult<SourceTaskPendingInventoryRespVO> getPendingInventoryTaskPage(
            SourceTaskPendingInventoryPageReqVO reqVO);

    /** 删除任务及其采集数据，保留货源库中的入库记录。 */
    void deleteTask(Long id, Long tenantId);

    /**
     * 暂停任务（status: 0 → 1）
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 任务不存在或状态不是运行中
     */
    void pauseTask(Long id);

    /**
     * 恢复任务（status: 1 → 0）
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 任务不存在或状态不是已暂停
     */
    void resumeTask(Long id);

    /**
     * 查询任务的执行批次列表
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 任务不存在
     */
    List<SourceExecutionRespVO> getExecutionList(Long taskId);

    /**
     * 查询批次搜索结果（仅有效数据）
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 任务不存在
     */
    List<SourceItemRespVO> getItemList(Long taskId, Integer executionNo);

    /**
     * 导出任务所有批次的搜索结果为CSV
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 任务不存在
     */
    void exportCsv(Long taskId, HttpServletResponse response);

    /**
     * 标记任务已查看：记录最后查看的批次号（全局共享，仅当批次号更大时更新）。
     */
    void markViewed(Long id, Integer executionNo);

    /**
     * 立即执行任务：重新提交给 Python 服务
     * - 更新状态为运行中，清空 errorMessage
     * - 调用 pythonSourceNotifier 通知 Python
     * - 提交失败则设为状态3+errorMessage
     *
     * @throws io.github.qing936.xianyuops.framework.common.exception.ServiceException 任务不存在或状态不允许执行
     */
    void executeTask(Long id);

    /**
     * 定时扫描触发任务，不切换 Cookie，也不占用手动重试互斥。
     */
    void executeScheduledTask(Long id);

}
