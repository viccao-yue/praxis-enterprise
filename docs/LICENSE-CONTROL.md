# 开物管理端授权落地说明

状态：已实现。个人端仓库不改。

本文按 Dify Enterprise 的离线授权逻辑，落到当前管理端代码上。部署自己生成安装标识，厂商按这个标识签发授权，服务端验签后卡住席位，企业功能由授权开关决定。

| Dify Enterprise | 开物管理端 |
| --- | --- |
| 集群标识 | `deployment_licenses.installation_id`，第一次需要时生成，写入数据库 |
| 离线码 | 一份 JSON 许可证文件，Ed25519 签名 |
| 席位 = 部署内账号数，只在创建账号时拒绝 | 席位 = `members.active = true` 的行数，新建成员和重新启用时拒绝 |
| 未登录看不到配额 | 只有所有者和管理员能读 `GET /api/admin/license` |
| 企业功能开关 | `sharing`、`modelGateway`、`sessionView` |

组织内已有的所有者、管理员、成员权限继续有效。许可证不代替这些权限。通讯录范围和「允许新的协作分享」也继续有效。新分享要同时通过组织策略和许可证的 `sharing` 开关。组织策略关闭时仍返回原文 `企业已关闭新的协作分享`，先看策略，再看许可证。

## 1. 行为

生产默认执行授权。`src/test/resources/application.properties` 设置 `开物Praxis.license.enforced=false`，现有测试保持现在的行为。只有 `LicenseControlTest` 用 `@SpringBootTest(properties=...)` 把它设回 `true`。`@SpringBootTest` 的 properties 覆盖测试用的 `application.properties`。

`enforced=false` 时，`allowLogin`、`actor` 里的授权检查、`requireUsable`、`requireSeat`、`requireFeature` 直接返回。`GET /api/admin/license` 仍返回安装标识和 `status=none`。

`enforced=true` 时：

| 状态 | 条件 | 所有者 | 其他成员 | 占席位的写操作 | 三个功能 |
| --- | --- | --- | --- | --- | --- |
| `none` | 还没有成功上传 | 可以登录并调用接口 | 登录和新请求都是 403 | 403 | 403 |
| `invalid` | 库存签名验签失败，或安装标识不符 | 可以登录并调用接口 | 登录和新请求都是 403 | 403 | 403 |
| `pending` | 当前时间早于 `notBefore` | 可以登录并调用接口 | 登录和新请求都是 403 | 403 | 403 |
| `expired` | 当前时间晚于 `notAfter` | 可以登录并调用接口 | 登录和新请求都是 403 | 403 | 403 |
| `expiring` | 在有效期内，且 `now.plus(14, ChronoUnit.DAYS).isAfter(notAfter)` | 允许 | 允许 | 按席位 | 按开关 |
| `active` | 在有效期内，且不是 `expiring` | 允许 | 允许 | 按席位 | 按开关 |

刚好还有 14 天时是 `active`。有效期两端都包含：`!now.isBefore(notBefore) && !now.isAfter(notAfter)`。`usable` 只包含 `active` 和 `expiring`。

授权是否可用，登录时看一次，之后每次 `AuthService.actor()` 再看一次。因此已经发出的 12 小时会话，在授权变为不可用后的下一次请求就会失效，不必等会话自己到期。`actor()` 只对非所有者做这个判断。所有者保持通过，以便打开企业信息页上传新文件。功能开关不在 `actor()` 里放行，所有者调用未购买的功能同样被拒绝。

固定错误文案。Spring Boot 3 把 `ResponseStatusException` 的原因放在响应 JSON 的 `detail`。测试断言 `detail`。管理端 `web/src/api.js` 已经先读 `detail`。

- 授权未上传、无效、过期，以及因此不能登录、不能占席位、不能使用功能：`企业授权无效，请所有者更新许可证`
- 授权有效，但该项开关是 `false`：`企业授权未包含此功能`
- 席位不够：`席位已满`
- 有效成员数已经多于许可证席位：`席位已超出授权，请所有者停用多余成员`
- 上传的席位数小于当前有效成员数：`席位少于当前有效成员数`
- 签名错误：`许可证签名无效`
- 安装标识不符：`许可证与此安装标识不符`
- 文件内容不符合第 2 节：`许可证格式无效`

登录失败用 403，不用 401。401 留给账号或密码错误。上传格式问题用 400。席位数小于当前人数用 409。

个人端登录在 `apps/desktop/src/enterprise-auth.ts`，调用管理端 `POST /api/auth/login`。403 时显示响应里的 `detail`；没有这句时仍显示 `企业登录失败 (403)`。401 仍表示账号或密码错误。管理端网页登录同样看得到 `detail`。

已有分享的查看和回复不看 `sharing` 开关。授权一旦不可用，非所有者在 `actor()` 就被拒绝，回复和查看也会停。只有授权仍然有效时，关闭 `sharing` 才只挡住新的分享。模型管理页可以保存内部 Key。成员真正调用模型时才看 `modelGateway`。

## 2. 许可证文件

文件是一个 JSON 对象，且只能有三个字段：`v`、`payload`、`signature`。`v` 必须是数字 `1`。

`payload` 是字符串，共 8 行，用 `\n` 连接，最后一行后面没有换行。顺序固定：

```text
customer=开物示例
installationId=550e8400-e29b-41d4-a716-446655440000
notBefore=2026-10-23T00:00:00Z
notAfter=2027-10-23T00:00:00Z
seats=50
sharing=true
modelGateway=true
sessionView=false
```

解析每一行时只在第一个 `=` 处切开，值里可以再出现 `=`。签发和验签必须共用 `LicenseDocument.canonicalPayload()`，禁止两边各写一套拼接。

字段规则：

- `customer`：1 到 80 个字符，不能含有 `\n` 或 `\r`，首尾不能是空格。
- `installationId`：小写 UUID，符合 `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`。
- `notBefore`、`notAfter`：`Instant.parse` 能解析的 UTC 时间，例如 `2026-10-23T00:00:00Z`。`notBefore` 不晚于 `notAfter`。
- `seats`：十进制整数，范围 1 到 100000，不允许加号、减号和前导零。`0` 不表示无限。
- 三个功能：只能是 `true` 或 `false`。

`signature` 是对 `payload.getBytes(UTF-8)` 的 Ed25519 签名，Base64 URL、无填充。解码后必须是 64 字节。64 字节签名编码后是 86 个字符，数据库列用 `varchar(128)` 足够。

验签使用 JDK 17 的 `KeyFactory.getInstance("Ed25519")` 和 `Signature.getInstance("Ed25519")`。不引入 BouncyCastle。公钥是 SPKI PEM，头尾为 `BEGIN PUBLIC KEY` / `END PUBLIC KEY`。去掉头尾和空白后做普通 Base64 解码，再用 `X509EncodedKeySpec`。私钥是 PKCS#8 PEM，只用签发命令读取。

每次判断授权都重新验签数据库里的 `payload` 和 `signature`。读路径上验签失败或安装标识不符时，状态是 `invalid`，HTTP 仍是 200。不相信表里的席位和开关列。这两列只在上传成功时写成和验签结果相同的值，供排查。`view` 的席位、时间和开关来自本次验签结果，不来自这两列。`payload` 为空表示还没上传，状态是 `none`，不要拿空字符串去验签。

## 3. 密钥

安装包里的验签公钥放在 `server/src/main/resources/license-public.pem`。这就是客户服务器用来验签的那把公钥，不是用完可弃的样例。私钥路径由环境变量 `开物Praxis_LICENSE_PRIVATE_KEY` 给出，不进 Git。在 `.gitignore` 增加 `license-private.pem`。

生成命令：

```sh
openssl genpkey -algorithm ED25519 -out license-private.pem
openssl pkey -in license-private.pem -pubout -out server/src/main/resources/license-public.pem
```

发布安装包前做一次成对检查：用这把私钥签发一份测试 payload，再用包内的 `license-public.pem` 验签。验签失败就不能交付。更换公钥时，已发出的许可证全部失效，必须重新签发。

测试不读这份私钥。`LicenseControlTest` 在内存中用 `KeyPairGenerator.getInstance("Ed25519")` 生成一对，并把公钥交给 `LicenseVerifier`。生产代码的 `LicenseVerifier` 只从 classpath 的 `license-public.pem` 加载。上下文启动时就要能读到这个文件，所以它必须先提交，所有测试才能启动。测试替换这个 Bean，不把测试私钥写进 `src/main`。

## 4. 数据库

在下面四个位置增加同一张表。不要改已有的 V001 到 V007。

- `server/src/main/resources/schema.sql`
- `server/src/main/resources/schema-mysql.sql`
- `server/src/main/resources/schema-sqlite.sql`
- `deploy/postgres/migrations/V008__deployment_license.sql`

Postgres 迁移给已有库用。`scripts/migrate-postgres.py` 按文件名 `V[0-9][0-9][0-9]__*.sql` 扫描，`V008` 会被执行。三个 schema 文件给新库初始化用。MySQL 语句使用和现有表一样的外键写法。

```sql
create table if not exists deployment_licenses (
  organization_id varchar(36) primary key,
  installation_id varchar(36) not null unique,
  payload text,
  signature varchar(128),
  customer_name varchar(80),
  not_before timestamp,
  not_after timestamp,
  seats integer,
  sharing boolean,
  model_gateway boolean,
  session_view boolean,
  uploaded_at timestamp,
  foreign key (organization_id) references organizations(id)
);
```

`schema.sql` 和 `schema-sqlite.sql` 可以保持文件里现有的 inline `references` 风格。MySQL 文件跟该文件已有的 `foreign key` 风格。

已有组织没有这行记录。`LicenseService.ensureInstallation(organizationId)` 在没有行时插入：`installation_id` 用 `UUID.randomUUID()`，许可证各列为空。并发插入撞上主键时再读一次。`AuthService.bootstrap` 创建组织和管理员之后立刻调用它。之后每次 `GET /api/admin/license` 和每次授权判断也调用它，这样已经初始化过的旧库不用手工补行。

升级已经在运行的管理端后，默认 `enforced=true`。重启后除所有者以外的成员不能登录，三个功能不可用，直到所有者上传许可证。不要为了兼容旧库自动写入一张永久许可证。

## 5. 服务端类型

新增这些类。除 `LicenseController` 外都在 `com.kaiwu.praxis.admin.license`：

- `LicenseVerifier`：只做密码学校验。构造参数是 `PublicKey`，自己不读文件。`Verified verify(String payload, String signature)` 在签名错误或正文不符合第 2 节时抛 400，文案用第 1 节。这个异常只允许从上传接口冒出去。
- `LicenseDocument`：解析后的 `customer`、`installationId`、`notBefore`、`notAfter`、`seats`、三个 boolean。提供 `canonicalPayload()`。
- `LicenseService`：读库、验签、计算状态、席位和功能判断。它不依赖 `AuthService`，避免循环注入。读库时捕获 `verify` 的 400，以及安装标识不符，转成状态 `invalid`，不再向外抛 400。
- `LicenseController`：放在包 `com.kaiwu.praxis.admin`，不放在 `license` 子包。上传和查询由它完成。
- `IssueLicense`：只含 `main`，不是 Spring Bean。

`LicenseKeyConfiguration` 提供唯一的 `@Bean PublicKey licensePublicKey()`，从 classpath 的 `license-public.pem` 加载。`LicenseControlTest` 用 `@TestConfiguration` 再声明一个方法名不是 `licensePublicKey` 的 `@Primary` `PublicKey` Bean，放内存中生成的公钥。同名方法会被 Spring 拒绝注册。不要用 `@MockBean` 替换 `LicenseVerifier`。

`LicenseService` 对外只有这些方法：

```text
View view(String organizationId)
void replace(String organizationId, String payload, String signature)
boolean usable(String organizationId)
void requireUsable(String organizationId)
void requireSeat(String organizationId)
void requireFeature(String organizationId, Feature feature)
void allowLogin(String organizationId, String role)
```

`Feature` 是 `SHARING`、`MODEL_GATEWAY`、`SESSION_VIEW`。

`view` 返回：

```json
{
  "installationId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "none",
  "customer": null,
  "notBefore": null,
  "notAfter": null,
  "seats": null,
  "seatsUsed": 1,
  "features": { "sharing": false, "modelGateway": false, "sessionView": false }
}
```

`seatsUsed` 永远是 `select count(*) from members where organization_id=? and active=true`。所有者是有效成员，算一个席位。`status` 不是 `active` 或 `expiring` 时，`features` 三个值都返回 `false`，即使库里留着旧开关。`status` 为 `invalid` 时仍返回这一行的 `installationId`，`customer`、`notBefore`、`notAfter`、`seats` 为 null，HTTP 是 200。所有者靠这个标识重新申请许可证。

`requireUsable` 在不可用时抛 403 `企业授权无效，请所有者更新许可证`。

`requireFeature` 先判断授权可用，再判断有效人数没有超过席位。超过时抛 403 `席位已超出授权，请所有者停用多余成员`。授权可用、人数未超过、但开关是 `false` 时，抛 403 `企业授权未包含此功能`。

`requireSeat` 先 `requireUsable`，再锁行，再数人数。`seatsUsed >= seats` 时抛 403 `席位已满`。判断发生在插入或重新启用之前，所以用的是「当前人数再加 1 是否超过」。

锁的实现：`requireSeat` 由已经标了 `@Transactional` 的成员创建或更新调用，传播行为保持默认的 `REQUIRED`，禁止 `REQUIRES_NEW`。先锁住本组织的许可证行，再数 `members`。插入或重新启用成功后，`confirmSeats` 在同一个事务里再数一次；人数已经超过席位就抛出原来的 `席位已满`，事务回滚。换证同样先锁这一行，并且 `update` 的条件里再次比较席位和当前有效人数，条件不成立时不写库，返回 409。这样换证和加人交错时，后提交的一方会看到先提交的人数。SQLite 的 `lock` 不加 `for update`，写操作仍由数据库的单写者串起来；人数检查放在写入之后，所以超卖会被回滚。H2、PostgreSQL 和 MySQL 的 `lock` 会加 `for update`。

`replace` 验签后核对 `installationId`。这里的验签失败继续抛 400，因为这是在接收一份新文件。`seats < seatsUsed` 时抛 409，不写库。成功时在同一事务更新该行。`replace` 不写审计，它没有操作者。

`LicenseController` 在 `replace` 成功后调用 `MemberController.audit(actor, "license.uploaded", organizationId)`。把现在包内可见的 `audit` 改成 `public`，子包里的服务不调用它。审计文案在 `web/src/App.vue` 的 `auditLabel` 增加 `license.uploaded: 更新企业授权`。非所有者上传返回 403，`detail` 是 `只有所有者可以更新企业授权`。

`allowLogin` 与员工端 `actor()` 使用同一个判断。角色是 `OWNER` 时直接返回。其他角色在授权不可用时抛 403 `企业授权无效，请所有者更新许可证`。授权仍有效、但有效成员数已经大于席位数时，抛 403 `席位已超出授权，请所有者停用多余成员`。人数等于席位数时可以登录。

企业控制台页面不看许可证。`POST /api/auth/browser-login` 发出的会话带 `channel=console`。所有者和管理员用这个会话访问 `/api/admin/`、`/api/auth/me`、`/api/auth/logout`、`/api/auth/change-password` 时不检查授权，因此成员管理、会话查阅、模型配置、企业信息在 `none`、`invalid`、`pending`、`expired`、`expiring`、`active` 以及人数超出席位时都能用。同一枚控制台会话访问协作、同事目录或其他员工端接口时，仍走 `allowLogin`。普通成员即使从控制台地址登录也要检查。员工端继续使用 `POST /api/auth/login`，`channel=desktop`。两种会话互不影响。模型实际调用和新的协作分享仍看许可证。

## 6. 接口

`GET /api/admin/license`

调用者经过 `auth.actor`、`requireReady`、`requireAdmin`。所有者和管理员都能看。普通成员 403。库中记录验签失败时仍返回 200，`status` 是 `invalid`，并带上原来的 `installationId`。授权不可用时，所有者仍能看，因为 `actor()` 放行所有者。

`PUT /api/admin/license`

只有 `OWNER`。请求体就是第 2 节的 JSON。管理员和其他成员返回 403，`detail` 是 `只有所有者可以更新企业授权`。成功返回 `view`。上传内容验签失败返回 400。库里已经存在的坏记录不影响这次上传，新文件合法就覆盖它。

## 7. 接到现有入口

改动只加判断，不重写原来的权限。

`AuthService.login`：密码和 `active` 校验通过之后，员工端会话在写入前调用 `allowLogin(org, role)`。非所有者在授权不可用，或有效人数已经超过席位时，不能拿到员工端会话。控制台会话对所有者和管理员跳过这一步。授权开启时，同一成员在同一个 `channel` 里只保留最新的一个会话：登录先锁住该成员行，只删掉同一渠道尚未过期的旧会话，再写入新会话。控制台登录不会顶掉员工端，员工端登录也不会顶掉控制台。授权关闭时不删旧会话。

`MemberController` 的席位检查和 `AdminSessionsController` 的 `sessionView` 检查，在 `consoleOperator()` 为真时跳过。协作分享和模型网关调用不跳过。

`AuthService.actor()`：查出有效成员之后、返回之前，对非所有者调用和 `allowLogin` 相同的判断。`CollaborationController.ready`、`AuthController.me` 以及各管理接口都经过 `actor()`，所以回复分享、查看已有分享、读取通知都会在授权失效后的下一次请求停住。不要把这个判断放进 `requireReady`。`requireReady` 仍然只负责强制修改初始密码。

`MemberController.create`：在 `insert into members` 之前调用 `requireSeat`。该方法已经是 `@Transactional`。直接用 SQL 插入的测试因为 `enforced=false` 不受影响。

`MemberController.update`：只有 `before.active()==false` 且本次 `active==true` 时调用 `requireSeat`。停用成员不检查席位。停用仍删除该成员的 `auth_sessions`。页面上的批量启用是逐个 `PATCH`，每个请求自己提交。锁行保证后一个请求看到前一个已经占用的席位。

`CollaborationController.send`：把 `sender_id + request_key` 查重移到功能判断之前。同一把 `request_key` 已经成功发过，并且收件人和摘要没变时，直接返回原来的记录，不再看 `sharing`。新的发送在插入前调用 `requireFeature(SHARING)`。

`CollaborationMaterialsController.send`：继续先调用 `collaboration.send`。随后如果 `collaboration_materials` 还没有这一行，在插入前调用 `requireFeature(SHARING)`。已有行且内容相同，是重试，不再看 `sharing`。已有行但内容不同，保持现在的 409 `Material retry differs from original`。这样不能在关闭分享之后，给一个早就建好、但还没有材料的交接补上文件。

`ModelGateway.authorize`：内部 Key 解析出组织之后调用 `requireFeature(MODEL_GATEWAY)`。这个入口不经过 `actor()`，所以授权过期时也靠 `requireFeature` 里的 `requireUsable` 拒绝。`GET/PUT /api/admin/model-gateway` 和查看 Key 不调用它。

`AdminSessionsController.list` 和 `detail`：管理员身份确认之后调用 `requireFeature(SESSION_VIEW)`。关闭时列表和正文都是 403，文案是 `企业授权未包含此功能`。授权本身不可用时，非所有者已经在 `actor()` 被拒绝。所有者看到的是功能那句文案。

同事目录、成员资料、部门、操作记录这些接口不增加功能开关判断。授权不可用时，非所有者仍然会被 `actor()` 拒绝。

## 8. 签发命令

在 `server/pom.xml` 增加 `exec-maven-plugin`，`mainClass` 为 `com.kaiwu.praxis.admin.license.IssueLicense`。

```sh
export 开物Praxis_LICENSE_PRIVATE_KEY=/绝对路径/license-private.pem
mvn -f server/pom.xml -q compile exec:java -Dexec.args="--customer 开物示例 --installation-id 550e8400-e29b-41d4-a716-446655440000 --not-before 2026-10-23T00:00:00Z --not-after 2027-10-23T00:00:00Z --seats 50 --sharing true --model-gateway true --session-view true"
```

标准输出只打印许可证 JSON。参数缺漏或私钥读不到时，进程以非 0 退出，错误写到标准错误。`not-before` 可省略，省略时用命令启动时的 `Instant.now()` 截到秒，并且格式化成 `Instant.parse` 能读回的 UTC 字符串。三个功能参数省略时是 `false`。显式传入时只能是 `true` 或 `false`，其他写法签发失败，不能悄悄当成关闭。

Maven 可能把 `exec.args` 里的空格拆开。`customer` 含空格时改用无空格名称，或在 `IssueLicense` 里同时接受环境变量 `开物Praxis_LICENSE_CUSTOMER`。实现时用一条不含空格的 customer 做签发自测，确认标准输出是第 2 节的 JSON。

## 9. 管理页

企业信息页就是 `web/src/App.vue` 里 `section v-else class="content-panel org-profile"` 这一段，菜单项 `settings` 落在这个 `v-else` 上。不要新造一个找不到的 `page==='settings'` 分支。

在现有企业名称和当前账号下面增加：

- 安装标识，带复制。
- 状态：未授权、授权无效、尚未生效、已过期、将于某日到期、有效至某日。对应 `none`、`invalid`、`pending`、`expired`、`expiring`、`active`。
- 客户名称、席位 `seatsUsed / seats`。未授权时席位显示 `seatsUsed / —`。
- 三个功能的开或关。
- 所有者看到文件选择。选择 JSON 文件后 `PUT /api/admin/license`，成功后刷新这块数据。管理员只能看，不能上传。

进入企业信息页时请求 `GET /api/admin/license`。上传失败显示 `api.js` 抛出的 `Error.message`，其中已经包含服务端 `detail`。

## 10. 测试

新增 `server/src/test/java/com/kaiwu/praxis/admin/LicenseControlTest.java`。H2 内存库、`MockMvc`、独立的 bootstrap 管理员。属性里 `开物Praxis.license.enforced=true`。用第 5 节的 `@Primary` 公钥接入测试密钥。断言错误文案时读 JSON 的 `detail`。

下面每一条都是独立的 `@Test`。需要前面步骤的结果时，在本条里重新做一遍，不读取其他测试留下的数据。

测试里的人物：

- 所有者使用 bootstrap 账号。`AuthService.bootstrap` 插入的所有者 `must_change_password` 是 `false`。
- 交接的收件人不用 `POST /api/admin/members` 创建。那个接口会把 `must_change_password` 设为 `true`，而 `CollaborationController.send` 要求收件人 `active=true` 且 `must_change_password=false`，否则返回 `Choose another active colleague`。收件人用 `JdbcTemplate` 插入，密码用 `auth.encode`，`must_change_password` 写 `false`。
- 要验证普通成员的管理接口时，同样插入 `must_change_password=false` 的 `MEMBER`。刚通过 API 创建、还没改初始密码的成员会先被 `requireReady` 拒绝，`detail` 是 `Password change required`，不能用来断言授权。

席位算术：bootstrap 已经有 1 名所有者。许可证 `seats=2` 时，再创建 1 名成员成功，下一次创建是第 3 名有效成员，返回 403 `席位已满`。

必须覆盖：

1. 未上传时，所有者登录成功，成员登录 403，`detail` 是 `企业授权无效，请所有者更新许可证`。
2. 未上传时，`POST /api/admin/members` 返回同一句 403。`GET /api/admin/license` 的 `status` 是 `none`，`seatsUsed` 至少为 1。
3. 用该库的 `installationId` 签发 2 个席位、三个功能都打开、`notAfter` 至少在 15 天之后的许可证。所有者上传后 `status` 是 `active`。再创建一名成员成功。再创建下一名返回 403 `席位已满`。
4. 停用第 2 名成员后可以再创建一名。此时有效人数再次达到 2。把刚才停用的成员重新启用，返回 403 `席位已满`。
5. 签名改一个字符，上传 400 `许可证签名无效`，库里的上一份 `payload` 不变。
6. `installationId` 换成别的 UUID，上传 400 `许可证与此安装标识不符`。
7. `seats` 小于当前有效人数，上传 409 `席位少于当前有效成员数`。
8. 成员在有效许可证下登录成功。再上传一份席位数仍不少于当前有效人数、其余字段相同、但 `notAfter` 早于当前时间的许可证。该成员再请求 `GET /api/auth/me` 返回 403 `企业授权无效，请所有者更新许可证`。所有者的 `GET /api/auth/me` 仍是 200，并且仍能 `GET /api/admin/license`。
9. 本条自己完成整个顺序。用 SQL 插入一名合格收件人。上传 `sharing=true` 的许可证，所有者 `POST /api/collaboration/handoffs` 成功。再上传一份许可证：只把 `sharing` 改成 `false` 并重新签名，`notBefore`、`notAfter`、席位和其他开关都不变。用同一把 `requestKey`、同一个收件人和同一段摘要重试，仍返回原来的交接。换一把新的 `requestKey` 再发，返回 403 `企业授权未包含此功能`。
10. 本条不依赖第 9 条的数据。用 SQL 插入另一名合格收件人。先上传 `sharing=true` 的许可证，只用交接接口建一条没有材料的交接。再按第 9 条的方式把 `sharing` 改为 `false`。然后 `POST /api/collaboration/materials`，请求里的 `recipientId`、`requestKey`、`summary` 必须和这条交接相同，这样 `send()` 会返回原交接，而不是再建一条。返回 403 `企业授权未包含此功能`，并且 `collaboration_materials` 没有新行。
11. `modelGateway=false`、其余授权有效时，测试上下文提供 `开物Praxis.secrets.encryption-key`。先 `PUT /api/admin/model-gateway` 写入至少 32 位、不含空白的内部 Key，再拿这把 Key 调用 `/api/model-gateway/v1/models`，返回 403 `企业授权未包含此功能`。这次 `PUT` 本身成功。
12. `sessionView=false`、其余授权有效时，`GET /api/admin/sessions` 返回 403 `企业授权未包含此功能`。
13. 先上传一份有效许可证，三个功能可以都打开。用 SQL 插入一名 `must_change_password=false` 的管理员和一名同样已经设好密码的普通成员。管理员 `GET /api/admin/license` 返回 200，`PUT` 返回 403，`detail` 是 `只有所有者可以更新企业授权`。普通成员 `GET` 返回 403，`detail` 是 `Admin role required`。

跑 `mvn -f server/pom.xml test`。现有测试因为 `enforced=false` 应继续通过。

## 11. 跟着 开物Praxis 升级

授权只放在本仓库。开物Praxis 客户端继续负责本机 Agent、会话和插件；本仓库继续负责账号、组织和许可证。升级 开物Praxis 时不要把许可证逻辑打进 `@deepseek-ai` 包，也不要改 DSH 的 Host。

两边只靠现有 HTTP 配合：

- 成员登录仍是 `POST /api/auth/login`。拒绝时 HTTP 403，原因在 Problem Details 的 `detail`。个人端只读取这个字段，账号密码错误仍用 401。
- 已登录请求仍带原来的 Bearer。授权失效后，下一次 `actor()` 拒绝非所有者。
- 协作、模型网关和会话查看仍走原来的路径，由本仓库在入口加开关。

开物Praxis 发布新版本后，本仓库的 `deployment_licenses` 表和已上传的许可证保留，不需要随桌面端重写。

个人端相对 开物Praxis 只留这一处：`apps/desktop/src/enterprise-auth.ts` 的 `loginFailure`。升级步骤写在个人端仓库 `scripts/praxis/UPGRADE-INVENTORY.md`。合并上游后保留这个函数和它的唯一调用，以及测试 `shows the admin license reason when login is forbidden`。不要把席位计算、许可证文件或验签放进桌面端，也不要做成 `@deepseek-ai` 的运行时补丁。

登录接口返回的 `detail` 会显示在登录页。已经登录之后，开物Praxis 原来的 `/api/auth/me` 核验和协作桥仍使用它们自己的失败文案；人数超出席位时，已打开的员工端会退出，员工再次登录才看到 `席位已超出授权，请所有者停用多余成员`。

## 12. 不做的事

- 不在桌面安装包里放许可证或私钥。个人端登录失败时只读取管理端返回的 `detail`，不自己判断席位和开关。
- 不绑 MAC、机器码或主机名。
- 不把私钥、签发记录或客户许可证提交到 Git。
- 不在未授权时自动生成一张永久许可证。
- 不把席位检查只放在前端。前端只负责展示和上传。
- 不把仓库里的公钥当成可替换的临时钥匙。它就是安装包的验签公钥。
