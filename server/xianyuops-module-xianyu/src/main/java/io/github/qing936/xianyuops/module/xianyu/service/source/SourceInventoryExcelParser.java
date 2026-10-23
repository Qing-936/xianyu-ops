package io.github.qing936.xianyuops.module.xianyu.service.source;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 货源库批量导入 - Excel 解析器
 *
 * <p>支持两种格式：</p>
 * <ul>
 *   <li>XML Spreadsheet 2003（*.xls，采集工具常见导出格式，文件头为 {@code <?xml}）</li>
 *   <li>标准 xlsx（OOXML，文件头为 PK）</li>
 * </ul>
 *
 * <p>统一解析为「工作表名称 → 行模型」映射，单元格缺失时为 null。
 * 商品主表与 SKU 明细分表存放时，调用方按工作表名称或表头特征选取对应 sheet。</p>
 */
@Slf4j
public final class SourceInventoryExcelParser {

    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    private static final String RELATIONSHIP_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private SourceInventoryExcelParser() {
    }

    /**
     * 解析 Excel 文件，返回第一个工作表的行模型（兼容旧调用）
     *
     * @param file 上传的 Excel 文件（XML 2003 或 xlsx）
     * @return 行模型，第一行为表头
     */
    public static List<List<String>> parse(MultipartFile file) throws IOException {
        Map<String, List<List<String>>> sheets = parseSheets(file);
        for (List<List<String>> rows : sheets.values()) {
            return rows;
        }
        return new ArrayList<>();
    }

    /**
     * 解析 Excel 文件，返回「工作表名称 → 行模型」映射（保持文件中的工作表顺序）
     *
     * @param file 上传的 Excel 文件（XML 2003 或 xlsx）
     * @return 工作表名称 → 行模型；无工作表时返回空 Map
     */
    public static Map<String, List<List<String>>> parseSheets(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的 Excel 文件不能为空");
        }
        byte[] bytes = file.getBytes();
        if (isXmlSpreadsheet(bytes)) {
            return parseXmlSpreadsheetSheets(bytes);
        }
        if (isZip(bytes)) {
            return parseXlsxSheets(bytes);
        }
        throw new IllegalArgumentException("不支持的文件格式，请上传 .xls（XML 表格）或 .xlsx 文件");
    }

    // ========== XML Spreadsheet 2003 ==========

    private static boolean isXmlSpreadsheet(byte[] bytes) {
        // 去除 UTF-8 BOM 后判断是否 XML
        int offset = bytes.length > 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String head = new String(bytes, offset, Math.min(512, bytes.length - offset), StandardCharsets.UTF_8).trim();
        return head.startsWith("<?xml") || head.startsWith("<Workbook");
    }

    private static Map<String, List<List<String>>> parseXmlSpreadsheetSheets(byte[] bytes) throws IOException {
        try {
            Document doc = parseXml(bytes);
            // 按 Worksheet 分段，避免多工作表时行数据被拼接到一起
            NodeList worksheets = doc.getElementsByTagNameNS("*", "Worksheet");
            Map<String, List<List<String>>> result = new LinkedHashMap<>();
            if (worksheets.getLength() == 0) {
                result.put("Sheet1", parseXmlRows(doc.getDocumentElement()));
                return result;
            }
            for (int i = 0; i < worksheets.getLength(); i++) {
                Element worksheet = (Element) worksheets.item(i);
                String name = StrUtil.blankToDefault(attributeByLocalName(worksheet, "Name"), "Sheet" + (i + 1));
                result.put(name, parseXmlRows(worksheet));
            }
            return result;
        } catch (Exception e) {
            log.warn("[parseXmlSpreadsheetSheets][解析 XML 表格失败]", e);
            throw new IOException("解析 Excel（XML 格式）失败：" + e.getMessage(), e);
        }
    }

    private static List<List<String>> parseXmlRows(Element root) {
        NodeList rows = root.getElementsByTagNameNS("*", "Row");
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            NodeList cells = row.getElementsByTagNameNS("*", "Cell");
            List<String> rowData = new ArrayList<>();
            int lastIndex = -1;
            for (int j = 0; j < cells.getLength(); j++) {
                Element cell = (Element) cells.item(j);
                // 优先使用 ss:Index 定位列，否则按顺序
                int colIndex = lastIndex + 1;
                String indexAttr = attributeByLocalName(cell, "Index");
                if (StrUtil.isNotBlank(indexAttr)) {
                    try {
                        colIndex = Integer.parseInt(indexAttr.trim()) - 1;
                    } catch (NumberFormatException ignored) {
                        // 忽略非法 Index，按顺序
                    }
                }
                while (rowData.size() < colIndex) {
                    rowData.add(null);
                }
                rowData.add(readXmlCellText(cell));
                lastIndex = colIndex;
            }
            result.add(rowData);
        }
        return result;
    }

    /** 读取 Cell 下第一个 Data 的文本（XML 2003 的 Data 可能有子元素如 Font） */
    private static String readXmlCellText(Element cell) {
        NodeList dataList = cell.getElementsByTagNameNS("*", "Data");
        if (dataList.getLength() == 0) {
            return null;
        }
        Element data = (Element) dataList.item(0);
        String text = data.getTextContent();
        return text == null ? null : text.trim();
    }

    /** 按属性本地名读取（兼容 ss:Name / ss:Index 这类带前缀属性） */
    private static String attributeByLocalName(Element element, String localName) {
        if (element == null) {
            return null;
        }
        String value = element.getAttribute(localName);
        if (StrUtil.isNotBlank(value)) {
            return value;
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            String name = attr.getLocalName() != null ? attr.getLocalName() : attr.getNodeName();
            if (localName.equals(name)) {
                return attr.getNodeValue();
            }
        }
        return null;
    }

    // ========== xlsx (OOXML) ==========

    private static boolean isZip(byte[] bytes) {
        if (bytes.length < 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if ((bytes[i] & 0xFF) != (ZIP_MAGIC[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, List<List<String>>> parseXlsxSheets(byte[] bytes) throws IOException {
        try {
            Map<String, byte[]> entries = readZipEntries(bytes);
            List<String> sharedStrings = entries.containsKey("xl/sharedStrings.xml")
                    ? parseSharedStrings(entries.get("xl/sharedStrings.xml")) : null;
            Map<String, List<List<String>>> result = new LinkedHashMap<>();
            for (SheetRef ref : resolveSheetRefs(entries)) {
                byte[] sheetXml = entries.get(ref.path);
                if (sheetXml == null) {
                    continue;
                }
                result.put(ref.name, parseSheet(new String(sheetXml, StandardCharsets.UTF_8), sharedStrings));
            }
            if (result.isEmpty()) {
                throw new IOException("未找到工作表数据（xl/worksheets/sheet*.xml）");
            }
            return result;
        } catch (IOException e) {
            log.warn("[parseXlsxSheets][解析 xlsx 失败]", e);
            throw e;
        }
    }

    private static Map<String, byte[]> readZipEntries(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    /** 工作表引用：显示名称 + zip 内路径 */
    private static final class SheetRef {
        private final String name;
        private final String path;

        private SheetRef(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    /**
     * 通过 workbook.xml + workbook.xml.rels 解析工作表名称与文件路径的对应关系；
     * 解析失败时按文件名排序兜底（Sheet1、Sheet2…）。
     */
    private static List<SheetRef> resolveSheetRefs(Map<String, byte[]> entries) {
        Map<String, String> relTargets = parseWorkbookRelations(entries.get("xl/_rels/workbook.xml.rels"));
        List<SheetRef> refs = new ArrayList<>();
        byte[] workbookXml = entries.get("xl/workbook.xml");
        if (workbookXml != null) {
            try {
                Document doc = parseXml(workbookXml);
                NodeList sheetNodes = doc.getElementsByTagNameNS("*", "sheet");
                for (int i = 0; i < sheetNodes.getLength(); i++) {
                    Element sheet = (Element) sheetNodes.item(i);
                    String name = StrUtil.blankToDefault(attributeByLocalName(sheet, "name"), "Sheet" + (i + 1));
                    String relId = sheet.getAttributeNS(RELATIONSHIP_NS, "id");
                    if (StrUtil.isBlank(relId)) {
                        relId = attributeByLocalName(sheet, "id");
                    }
                    String target = relTargets.get(relId);
                    if (StrUtil.isBlank(target)) {
                        // 没有 rels 时按顺序映射 sheetN.xml
                        target = "worksheets/sheet" + (i + 1) + ".xml";
                    }
                    refs.add(new SheetRef(name, normalizeTarget(target)));
                }
            } catch (Exception e) {
                log.warn("[resolveSheetRefs][解析 workbook.xml 失败，按文件名兜底]", e);
                refs.clear();
            }
        }
        if (!refs.isEmpty()) {
            return refs;
        }
        entries.keySet().stream()
                .filter(name -> name.matches("xl/worksheets/sheet\\d+\\.xml"))
                .sorted(Comparator.comparingInt(SourceInventoryExcelParser::sheetFileOrder))
                .forEach(path -> refs.add(new SheetRef("Sheet" + (sheetFileOrder(path)), path)));
        return refs;
    }

    private static int sheetFileOrder(String path) {
        String digits = path.replaceAll("\\D+", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Map<String, String> parseWorkbookRelations(byte[] relsXml) {
        Map<String, String> result = new HashMap<>();
        if (relsXml == null) {
            return result;
        }
        try {
            Document doc = parseXml(relsXml);
            NodeList relations = doc.getElementsByTagNameNS("*", "Relationship");
            for (int i = 0; i < relations.getLength(); i++) {
                Element relation = (Element) relations.item(i);
                String id = attributeByLocalName(relation, "Id");
                String target = attributeByLocalName(relation, "Target");
                if (StrUtil.isNotBlank(id) && StrUtil.isNotBlank(target)) {
                    result.put(id, target);
                }
            }
        } catch (Exception e) {
            log.warn("[parseWorkbookRelations][解析 workbook.xml.rels 失败]", e);
        }
        return result;
    }

    /** target 相对 xl/ 目录，可能是 /xl/worksheets/sheet1.xml 或 worksheets/sheet1.xml */
    private static String normalizeTarget(String target) {
        String path = target.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!path.startsWith("xl/")) {
            path = "xl/" + path;
        }
        return path;
    }

    private static List<String> parseSharedStrings(byte[] xml) throws IOException {
        List<String> result = new ArrayList<>();
        try {
            Document doc = parseXml(xml);
            NodeList sis = doc.getElementsByTagNameNS("*", "si");
            for (int i = 0; i < sis.getLength(); i++) {
                Element si = (Element) sis.item(i);
                StringBuilder sb = new StringBuilder();
                NodeList ts = si.getElementsByTagNameNS("*", "t");
                for (int j = 0; j < ts.getLength(); j++) {
                    sb.append(ts.item(j).getTextContent());
                }
                result.add(sb.toString());
            }
            return result;
        } catch (Exception e) {
            log.warn("[parseSharedStrings][解析 sharedStrings 失败]", e);
            throw new IOException("解析 xlsx 共享字符串失败", e);
        }
    }

    private static List<List<String>> parseSheet(String sheetXml, List<String> sharedStrings) throws IOException {
        List<List<String>> result = new ArrayList<>();
        try {
            Document doc = parseXml(sheetXml.getBytes(StandardCharsets.UTF_8));
            NodeList rows = doc.getElementsByTagNameNS("*", "row");
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                NodeList cells = row.getElementsByTagNameNS("*", "c");
                List<String> rowData = new ArrayList<>();
                for (int j = 0; j < cells.getLength(); j++) {
                    Element cell = (Element) cells.item(j);
                    int colIndex = columnIndex(cell.getAttribute("r"));
                    while (rowData.size() < colIndex) {
                        rowData.add(null);
                    }
                    rowData.add(readXlsxCellText(cell, sharedStrings));
                }
                result.add(rowData);
            }
            return result;
        } catch (Exception e) {
            log.warn("[parseSheet][解析 xlsx 工作表失败]", e);
            throw new IOException("解析 xlsx 工作表失败", e);
        }
    }

    private static String readXlsxCellText(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        NodeList vs = cell.getElementsByTagNameNS("*", "v");
        if ("s".equals(type)) {
            if (vs.getLength() > 0 && sharedStrings != null) {
                try {
                    int idx = Integer.parseInt(vs.item(0).getTextContent());
                    return idx >= 0 && idx < sharedStrings.size() ? sharedStrings.get(idx) : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
        // 行内字符串（inlineStr）或数字
        NodeList iss = cell.getElementsByTagNameNS("*", "is");
        if ("inlineStr".equals(type) && iss.getLength() > 0) {
            Element inline = (Element) iss.item(0);
            StringBuilder sb = new StringBuilder();
            NodeList ts = inline.getElementsByTagNameNS("*", "t");
            for (int j = 0; j < ts.getLength(); j++) {
                sb.append(ts.item(j).getTextContent());
            }
            return sb.toString();
        }
        if (vs.getLength() > 0) {
            String text = vs.item(0).getTextContent();
            return text == null ? null : text.trim();
        }
        return null;
    }

    /** 单元格引用转列索引：A→0，B→1，…，AA→26 */
    private static int columnIndex(String ref) {
        int index = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                index = index * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return index > 0 ? index - 1 : 0;
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

}
