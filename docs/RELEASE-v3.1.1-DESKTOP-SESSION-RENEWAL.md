# 开物 Praxis 企业控制台 v3.1.1 变更记录

记录日期：2026 年 11 月 19 日。本文记录企业控制台从 `v3.1` 到 `v3.1.1` 的实际改动、升级要求和交付结果。

## 这次解决了什么

原来桌面端的企业登录会话在创建约 12 小时后失效。新版控制台允许支持续期的桌面客户端在会话到期前延长同一次登录。例如，桌面端如果在前一晚及时发出续期请求，员工第二天回来时可能不用重新登录。**续期不是永久登录，也不能恢复已经过期的会话**：如果桌面端关闭、电脑休眠或内网断开，且到期前没有完成续期，员工仍需重新登录。

`v3.1` 已交付的离线 HTTPS 和 `enterprise-connection.praxis` 导入机制保持不变。仅为本次续期升级，不需要重新导出或导入连接文件。本次打包对象是企业控制台，不包含桌面客户端；续期需要桌面端也支持该功能。

## 服务端实际改动

- `AuthController.java` 新增 `POST /api/auth/desktop/renew`。请求使用现有的 `Authorization: Bearer` 令牌，成功响应返回 `expiresAt`（Unix 毫秒时间戳），并设置 `Cache-Control: no-store`。
- `AuthService.java` 在新建桌面会话时返回 `expiresAt` 和 `renewalLimit`。初始有效期为 12 小时；在剩余 4 小时内收到符合条件的请求时，服务端将到期时间延至本次续期后的 12 小时，但不得超过首次登录后 72 小时。提前请求时返回当前到期时间。续期更新原会话，不签发新的令牌。
- 服务端只延长仍然有效、未撤销且成员状态满足要求的桌面会话。数据库更新不会重新创建被删除的会话，也不会把到期时间改短。管理网页会话不使用桌面续期接口。
- 新增 PostgreSQL 迁移 `V010__desktop_session_renewal.sql`，为 `auth_sessions` 添加可空字段 `desktop_renewal_limit`。生产离线包由维护账号执行迁移，应用运行账号不需要改表权限；若未执行迁移，控制台启动会明确提示。非 PostgreSQL 环境沿用应用启动时补列的方式。
- CI 中另修复了一条偶发失败的许可证测试：测试修改签名时保证新字符不同于原字符，确保确实构造了无效签名。生产许可证校验逻辑没有改动。

## 已部署客户怎么升级

1. 使用 `v3.1.1` 客户离线交付包，解包前可用随附的 SHA-256 文件核对安装包。
2. PostgreSQL 部署须先执行交付包中的迁移，再启动新版控制台。交付包的一键启动流程会按此顺序执行；手动部署时应使用数据库维护账号执行迁移。
3. 让员工在支持续期的桌面客户端重新登录一次。旧版本创建的会话不会补写续期上限，因此不能续期；旧会话不会仅因添加字段就被立即注销。
4. 现场验收时，用新版桌面端登录测试账户，确认在续期窗口内发出请求后有效期被延长；退出登录后原令牌应不能续期。

## 验证与交付

- 服务端本地 `mvn verify` 通过：46 个用例，0 失败、0 错误、2 跳过；交付包边界测试通过。
- 新增或扩展的测试覆盖正常续期、续期上限、已撤销或过期会话拒绝续期、旧会话与网页登录会话的限制，以及 SQLite 更新原会话和 PostgreSQL 交付迁移包含新字段。迁移链回归测试使用 H2 的 PostgreSQL 兼容模式，不等同于对客户 PostgreSQL 实例做过升级验收。
- [PR #2](https://github.com/viccao-yue/praxis-enterprise/pull/2) 已合入 `main`，合并提交为 `ced17d3`。打包使用新标签 [`v3.1.1`](https://github.com/viccao-yue/praxis-enterprise/tree/v3.1.1)，没有覆盖 `v3.1`。
- [客户离线交付包：`praxis-offline-field`](https://github.com/viccao-yue/praxis-enterprise/actions/runs/44520192913/artifacts/14526179884)，包含 `.tgz` 和 `.sha256`。
- [控制台候选包：`kaiwu-praxis-admin-candidate`](https://github.com/viccao-yue/praxis-enterprise/actions/runs/44520149052/artifacts/14526219825)，供内部验证。

上述 GitHub Actions 产物于 **2026 年 12 月 3 日**到期。若需长期留档，请提前下载并连同校验文件一起保存。
