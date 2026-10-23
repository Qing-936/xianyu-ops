# 安全策略

## 报告问题

请通过 GitHub 仓库的 **Security → Report a vulnerability** 私下报告，不要创建公开 Issue，也不要在 Issue 中粘贴 Cookie、Token、数据库连接串或内网地址。

## 敏感信息

本项目不会提供任何真实凭据。以下文件已被 `.gitignore` 排除，请勿解除忽略或提交：

- `python/source/cookie.json`
- `python/xianyu-agent/.agent_token`
- `python/xianyu-agent/.machine_code`
- `python/xianyu-agent/accounts.json`
- `python/xianyu-agent/config.local.yaml`
- 任意 `.env`

## 部署建议

- 生产环境必须替换数据库密码与 `XIANYUOPS_ENCRYPT_PASSWORD`。
- 保持 `SECURITY_MOCK_ENABLE=false`；`PERMISSION_CHECK_ENABLE` 在完成角色权限配置后应设为 `true`。
- 管理端、后端与货源服务不要直接暴露到公网，建议通过 Nginx 统一入口并启用 HTTPS。
- 定期轮换 Agent Token、闲鱼 Cookie、邮件授权码与 DeepSeek/Dify Key。
