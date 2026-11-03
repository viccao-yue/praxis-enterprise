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

echo "==> 写入一键安装脚本与说明"
install -m 0755 "$ROOT/deploy/offline/install-on-customer.sh" "$BUNDLE/install-on-customer.sh"
cat > "$BUNDLE/先读我.txt" <<'EOF'
开物Praxis 企业控制台 · 纯内网离线包
================================

现场只做三步：

1) 把本目录整个拷到业务机，例如 /home/deploy/praxis-offline-field

2) SSH 登录业务机后执行（把 IP 换成业务机内网 IP）：

   cd /home/deploy/praxis-offline-field
   sudo -E DELIVERY_HOST=10.10.10.11 bash install-on-customer.sh

3) 浏览器打开 https://10.10.10.11/  （接受自签证书警告）
   账号见安装完成后的提示，或 /opt/kaiwu-praxis-admin/config/BOOTSTRAP-ONCE.txt

要求：
- Ubuntu Server 22.04 x86_64
- 机器上最好已有 Docker（本包已含镜像，不必访问外网）
- 若没有 Docker，且目录里有 ubuntu22-debs/，脚本会尝试离线安装

装完立刻备份：
  /opt/kaiwu-praxis-admin/config/secrets.env
  /opt/kaiwu-praxis-admin/config/tls/
EOF

if [[ "$WITH_DEBS" == "1" ]]; then
  echo "==> 下载 Ubuntu 22.04 deb（尽力而为）"
  mkdir -p "$BUNDLE/ubuntu22-debs"
  if command -v apt-get >/dev/null 2>&1; then
    (
      cd "$BUNDLE/ubuntu22-debs"
      apt-get download docker.io docker-compose-v2 containerd runc python3 openssl ca-certificates curl unzip 2>/dev/null || true
      # 再拉一层常见依赖名（失败忽略）
      apt-cache depends docker.io 2>/dev/null | awk '/Depends:/{print $2}' | grep -v '[<]' | sort -u \
        | xargs -r apt-get download 2>/dev/null || true
    )
    echo "deb 数量: $(ls -1 "$BUNDLE/ubuntu22-debs"/*.deb 2>/dev/null | wc -l)"
  else
    echo "当前系统无 apt-get，跳过 deb"
  fi
fi

echo "==> 打包 $OUT"
mkdir -p "$(dirname "$OUT")"
tar -C "$STAGE" -czf "$OUT" praxis-offline-field
python3 - <<PY
import hashlib, pathlib
p = pathlib.Path("$OUT")
print(f"wrote {p} bytes={p.stat().st_size} sha256={hashlib.sha256(p.read_bytes()).hexdigest()}")
PY
