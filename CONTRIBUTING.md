# 贡献指南

感谢参与 XianyuOps。

## 提交前检查

```bash
# 后端
mvn -q -DskipTests compile

# 管理端
cd web && pnpm install && pnpm exec vue-tsc --noEmit && pnpm build

# Python 语法
python -m compileall -q python
```

## 约定

- 后端分层：`controller` → `service` → `dal`，跨模块只通过 `api` 包调用。
- 新增字段时同步修改 DO/VO 与 `sql/mysql/` 中对应的 `CREATE TABLE`。
- 前端时间字段用 `dayjs` 格式化，金额统一从「分」转换为「元」展示。
- 不要提交任何真实账号、Cookie、Token、密钥、内网地址或生产域名。
- 不要提交 `node_modules`、`target`、`dist`、日志与本地虚拟环境。

## Pull Request

- 一个 PR 只解决一个问题，说明背景、改动范围与验证命令。
- 涉及数据库变更时，同时提供建表语句与存量库 `ALTER` 语句。
- 涉及 Agent 坐标/OCR 调整时，说明测试机型与窗口分辨率。
