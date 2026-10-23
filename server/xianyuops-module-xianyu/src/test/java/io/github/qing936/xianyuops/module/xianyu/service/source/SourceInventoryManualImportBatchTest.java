package io.github.qing936.xianyuops.module.xianyu.service.source;

import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.SourceInventoryController;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceInventoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("货源库批量人工入库")
class SourceInventoryManualImportBatchTest {

    @Test
    @DisplayName("manualImportBatch: 单条失败不应中断整批，返回成功/失败汇总")
    void manualImportBatchContinuesAfterSingleUrlFailures() throws Exception {
        SourceInventoryServiceImpl service = new SourceInventoryServiceImpl();
        PythonSourceNotifier notifier = mock(PythonSourceNotifier.class);
        ReflectionTestUtils.setField(service, "pythonSourceNotifier", notifier);
        ReflectionTestUtils.setField(service, "sourceInventoryMapper", mock(SourceInventoryMapper.class));

        String successUrl = "https://www.goofish.com/item?id=10001";
        String duplicateUrl = "https://www.goofish.com/item?id=10002";
        String invalidUrl = "https://www.goofish.com/item?id=10003";

        doThrow(new RuntimeException("该链接已入库: inventory_id=2"))
                .when(notifier).manualImport(duplicateUrl, "9527", 1L);
        doThrow(new RuntimeException("链接不是有效的闲鱼商品链接"))
                .when(notifier).manualImport(invalidUrl, "9527", 1L);

        Method batchMethod = assertDoesNotThrow(() ->
                        SourceInventoryServiceImpl.class.getMethod("manualImportBatch", List.class, String.class, Long.class),
                "SourceInventoryServiceImpl 应提供 manualImportBatch(List<String>, String, Long)");

        Object response = batchMethod.invoke(service, List.of(successUrl, duplicateUrl, invalidUrl), "9527", 1L);
        Class<?> responseClass = assertDoesNotThrow(() ->
                        Class.forName("io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryManualImportRespVO"),
                "批量人工入库应返回专门的响应 VO");
        assertInstanceOf(responseClass, response, "manualImportBatch 应返回批量人工入库响应对象");

        Method getTotalCount = responseClass.getMethod("getTotalCount");
        Method getSuccessCount = responseClass.getMethod("getSuccessCount");
        Method getFailedCount = responseClass.getMethod("getFailedCount");
        Method getResults = responseClass.getMethod("getResults");

        assertEquals(3, getTotalCount.invoke(response), "totalCount 应等于提交的有效链接数");
        assertEquals(1, getSuccessCount.invoke(response), "successCount 应统计成功条数");
        assertEquals(2, getFailedCount.invoke(response), "failedCount 应统计失败条数");

        Object resultsValue = getResults.invoke(response);
        assertInstanceOf(List.class, resultsValue, "results 应返回逐条处理结果");
        List<?> results = (List<?>) resultsValue;
        assertEquals(3, results.size(), "results 应为每条链接返回一条结果");

        Object duplicateResult = results.get(1);
        Method getSuccess = duplicateResult.getClass().getMethod("getSuccess");
        Method getMessage = duplicateResult.getClass().getMethod("getMessage");
        assertFalse((Boolean) getSuccess.invoke(duplicateResult), "重复链接应标记为失败");
        assertTrue(String.valueOf(getMessage.invoke(duplicateResult)).contains("已入库"),
                "重复链接应返回可读的失败原因");

        verify(notifier).manualImport(successUrl, "9527", 1L);
        verify(notifier).manualImport(duplicateUrl, "9527", 1L);
        verify(notifier).manualImport(invalidUrl, "9527", 1L);
    }

    @Test
    @DisplayName("控制器: 新增人工入库时仍应保留 GET /page 货源库分页接口")
    void controllerKeepsInventoryPageGetMapping() throws Exception {
        Method getPageMethod = SourceInventoryController.class.getMethod("getPage",
                Class.forName("io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryPageReqVO"));
        GetMapping getMapping = getPageMethod.getAnnotation(GetMapping.class);
        assertTrue(getMapping != null && List.of(getMapping.value()).contains("/page"),
                "getPage 必须继续映射 GET /page，否则“货源库”菜单无法正常展示");

        Method manualImportMethod = assertDoesNotThrow(() ->
                        SourceInventoryController.class.getMethod("manualImport",
                                Class.forName("io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryManualImportReqVO")),
                "控制器应暴露 manualImport 批量人工入库入口");
        PostMapping postMapping = manualImportMethod.getAnnotation(PostMapping.class);
        assertTrue(postMapping != null && List.of(postMapping.value()).contains("/manual-import"),
                "manualImport 必须映射 POST /manual-import");
    }
}
