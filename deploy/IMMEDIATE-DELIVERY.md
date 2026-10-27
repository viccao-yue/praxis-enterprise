# 马上对外交付 · 最小集合执行手册

对应差距清单第 7 节。优先走「一键交付」；打勾后再签客户验收。

## 主路径：Docker + 自签 HTTPS（推荐）

现场只需 Docker / Docker Compose。安装后默认：**PostgreSQL**、**自签 HTTPS**、**无源码明文口令**、**密钥落盘**、反代下 **Secure Cookie**。

```bash
# 1) 解压候选包到新目录（勿覆盖已有安装）
python3 scripts/install-admin-release.py ./kaiwu-praxis-admin-candidate.zip /opt/kaiwu-praxis-admin

# 2) 一条命令：生成密钥 → 自签证书 → 迁移 → 起栈
#    若员工会用局域网 IP/域名访问，首次前可设：export DELIVERY_HOST=192.168.x.x
cd /opt/kaiwu-praxis-admin
python3 scripts/delivery-bootstrap.py
```

依赖：本机 `docker` / `docker compose`、`python3`、`openssl`。

然后：

1. 浏览器打开 `https://<服务器IP或主机名>/`（接受自签证书警告一次）。  
2. 用 `admin` / `Sun55kong!` 登录（本轮暂不强制改密；口令也写在 `config/BOOTSTRAP-ONCE.txt`）。  
3. **离线备份** `config/secrets.env` 与 `config/tls/`（加密密钥、服务密钥、数据库口令、自签证书）。  
4. 控制台上传许可证；用真实 Desktop 做联调勾选（见下文）。

二次执行 `delivery-bootstrap.py` **不会**轮换已有 `config/secrets.env` 与 TLS 证书。

停止 / 再起（bootstrap 会写项目根目录 `.env`，一般不必再手传 `--env-file`）：

```bash
cd /opt/kaiwu-praxis-admin
docker compose -f deploy/docker-compose.delivery.yml down
docker compose -f deploy/docker-compose.delivery.yml up -d
# 若换库或怀疑 schema 未就绪，再执行一次：
# python3 scripts/delivery-bootstrap.py
```

### 仍须人工验收（无法装进默认配置）

- [ ] 许可证签发并在本环境上传成功  
- [ ] 个人端（Desktop）真实联调通过  
- [ ] （若走公司模型网关）已向客户说明临时容量并约定压测（见 §网关）

---

## 网关临时容量说明（写入客户说明）

当前网关为同步转发，单请求超时可达约 5 分钟，**无**产品级全局限流。千人若全走公司网关：

- **临时口径：** 首版按「工作时间同时推理会话不超过数十路」估算，超出可能出现排队或 502；扩容方式为增加管理端实例（共享同一 PostgreSQL）并在前置负载均衡。  
- **约定：** 交付后 2–4 周内安排登录风暴 + 网关并发 + 正文同步压测，再给出正式容量数字。

---

## 附录：不用 Compose 时的逐步排障

仅在无法使用 Docker 一键栈时按此手工做。

### A. PostgreSQL

```bash
export POSTGRES_MAINT_PASSWORD='替换为强密码'
docker compose -f deploy/docker-compose.postgres.yml up -d
export PGHOST=127.0.0.1 PGPORT=5432 PGDATABASE=kaiwu_praxis
export PGUSER=praxis_maint PGPASSWORD="$POSTGRES_MAINT_PASSWORD"
python3 scripts/migrate-postgres.py
# 创建 workdsh_app 后执行 deploy/postgres/runtime-grants.sql
```

管理端：`SPRING_PROFILES_ACTIVE=enterprise-postgres` + `JDBC_*`。

### B. HTTPS / Origin / Cookie

参考 `deploy/nginx-https.example.conf` 或交付栈内 `deploy/delivery/Caddyfile`。须传 `X-Forwarded-Proto https`。一键栈默认留空 `WORKDSH_ADMIN_WEB_ORIGIN`，由请求 Host 推断。

### C. 口令与密钥

```bash
python3 scripts/generate-field-secrets.py \
  --bootstrap-email admin \
  --origin https://admin.example.com \
  > /secure-path/praxis-admin.env
```

空库首次启动默认 `admin` / `Sun55kong!`（可用环境变量覆盖）；本轮暂不强制首次改密。

### D. 交付签字前自检

- [ ] 未使用 H2 作为客户生产库  
- [ ] HTTPS 可访问（自签可接受警告）  
- [ ] 能用 `admin` / `Sun55kong!` 登录（本轮暂不强制改密）  
- [ ] `config/secrets.env`（或等价三项密钥）已离线备份  
- [ ] 许可证上传成功  
- [ ] 个人端联调清单全部勾选  
- [ ] 客户已看到网关临时容量说明（若使用网关）
