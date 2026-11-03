#!/usr/bin/env bash
# 打「纯内网现场包」：候选 zip + amd64 镜像 tar + 一键安装脚本（+ 可选 deb）。
# 必须在能访问镜像仓库的 Linux/amd64 或 CI 上运行；Apple Silicon 请加 platform。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-}"
PLATFORM="${DOCKER_PLATFORM:-linux/amd64}"
WITH_DEBS="${WITH_DEBS:-0}"

if [[ -z "$OUT" ]]; then
  echo "用法: $0 /绝对路径/praxis-offline-field.tgz"
  exit 1
fi
OUT="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$OUT")"
if [[ -e "$OUT" ]]; then
  echo "输出已存在，拒绝覆盖: $OUT"
  exit 1
fi

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/praxis-offline-XXXXXX")"
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

BUNDLE="$STAGE/praxis-offline-field"
mkdir -p "$BUNDLE"

echo "==> 准备候选包"
CANDIDATE_SRC="$ROOT/dist/kaiwu-praxis-admin-candidate.zip"
if [[ ! -f "$CANDIDATE_SRC" ]]; then
  mkdir -p "$ROOT/dist"
  if [[ ! -f "$ROOT/server/target/kaiwu-praxis-admin-server-0.1.0-SNAPSHOT.jar" ]]; then
    echo "缺少 server JAR，请先: mvn -f server/pom.xml -DskipTests package"
    exit 1
  fi
  if [[ ! -f "$ROOT/web/dist/index.html" ]]; then
    echo "缺少 web/dist，请先: npm --prefix web ci && npm --prefix web run build"
    exit 1
  fi
  python3 "$ROOT/scripts/pack-admin-release.py" --output "$CANDIDATE_SRC"
fi
cp "$CANDIDATE_SRC" "$BUNDLE/kaiwu-praxis-admin-candidate.zip"

echo "==> 拉取并导出 Docker 镜像 ($PLATFORM)"
IMAGES=(postgres:16 eclipse-temurin:17-jre caddy:2)
for img in "${IMAGES[@]}"; do
  docker pull --platform "$PLATFORM" "$img"
done
docker save "${IMAGES[@]}" -o "$BUNDLE/praxis-docker-images.tar"

echo "==> 下载 Docker 静态程序（客户机无需预装 Docker）"
DOCKER_VER="${DOCKER_STATIC_VERSION:-27.5.1}"
COMPOSE_VER="${COMPOSE_STATIC_VERSION:-2.32.4}"
curl -fL --retry 3 -o "$BUNDLE/docker-static.tgz" \
  "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VER}.tgz"
curl -fL --retry 3 -o "$BUNDLE/docker-compose" \
  "https://github.com/docker/compose/releases/download/v${COMPOSE_VER}/docker-compose-linux-x86_64"
chmod 0755 "$BUNDLE/docker-compose"
install -m 0644 "$ROOT/deploy/offline/docker.service" "$BUNDLE/docker.service"
# sanity: tarball contains dockerd (avoid SIGPIPE from grep -q)
tar -tzf "$BUNDLE/docker-static.tgz" > "$STAGE/docker-static.list"
grep -q 'docker/dockerd' "$STAGE/docker-static.list"
echo "docker static $(wc -c < "$BUNDLE/docker-static.tgz") bytes, compose $(wc -c < "$BUNDLE/docker-compose") bytes"

echo "==> 写入一键安装脚本与说明"
install -m 0755 "$ROOT/deploy/offline/install-on-customer.sh" "$BUNDLE/install-on-customer.sh"
cat > "$BUNDLE/先读我.txt" <<'EOF'
开物Praxis 企业控制台 · 纯内网离线包
================================

客户机不需要预装 Docker，也不需要外网。

1) 把本目录整个拷到业务机，例如 /home/deploy/praxis-offline-field

2) SSH 登录后执行（把 IP 换成业务机内网 IP）：

   cd /home/deploy/praxis-offline-field
   sudo -E DELIVERY_HOST=10.10.10.11 bash install-on-customer.sh

3) 浏览器打开 https://10.10.10.11/  （接受自签证书警告）
   账号见 /opt/kaiwu-praxis-admin/config/BOOTSTRAP-ONCE.txt

系统要求：Ubuntu Server 22.04 x86_64，自带 python3 和 openssl（Server 默认有）。
本包自带 Docker 静态程序和镜像，脚本会自己安装并启动 Docker。

装完立刻备份：
  /opt/kaiwu-praxis-admin/config/secrets.env
  /opt/kaiwu-praxis-admin/config/tls/
EOF

echo "==> 打包 $OUT"
mkdir -p "$(dirname "$OUT")"
tar -C "$STAGE" -czf "$OUT" praxis-offline-field
python3 - <<PY
import hashlib, pathlib
p = pathlib.Path("$OUT")
print(f"wrote {p} bytes={p.stat().st_size} sha256={hashlib.sha256(p.read_bytes()).hexdigest()}")
PY
