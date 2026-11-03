#!/usr/bin/env bash
# 客户纯内网一键安装（U 盘离线包内使用）。
# 用法：
#   sudo -E DELIVERY_HOST=10.10.10.11 bash install-on-customer.sh
# 或（脚本会尝试猜本机 IP）：
#   sudo -E bash install-on-customer.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CANDIDATE="$ROOT/kaiwu-praxis-admin-candidate.zip"
IMAGES="$ROOT/praxis-docker-images.tar"
DEBS="$ROOT/ubuntu22-debs"
DEST="${INSTALL_DEST:-/opt/kaiwu-praxis-admin}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请用 root 执行，例如："
  echo "  sudo -E DELIVERY_HOST=你的业务机IP bash $0"
  exit 1
fi

if [[ ! -f "$CANDIDATE" ]]; then
  echo "缺少 $CANDIDATE"
  exit 1
fi
if [[ ! -f "$IMAGES" ]]; then
  echo "缺少 $IMAGES（离线 Docker 镜像包）"
  exit 1
fi

need_bin() {
  command -v "$1" >/dev/null 2>&1
}

echo "==> 检查 / 补齐基础命令"
if ! need_bin docker || ! docker compose version >/dev/null 2>&1 || ! need_bin python3 || ! need_bin openssl || ! need_bin unzip; then
  if [[ -d "$DEBS" ]] && ls "$DEBS"/*.deb >/dev/null 2>&1; then
    echo "尝试从 ubuntu22-debs 离线安装依赖…"
    dpkg -i "$DEBS"/*.deb || true
    apt-get install -f -y || true
  fi
fi

for bin in docker python3 openssl unzip; do
  if ! need_bin "$bin"; then
    echo "缺少命令: $bin"
    echo "请让客户 IT 预装 Docker / python3 / openssl / unzip，或提供可用的 ubuntu22-debs。"
    exit 1
  fi
done
if ! docker compose version >/dev/null 2>&1; then
  echo "缺少 docker compose（v2 插件）。请预装 docker-compose-v2。"
  exit 1
fi

HOST="${DELIVERY_HOST:-}"
if [[ -z "$HOST" ]]; then
  HOST="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
if [[ -z "$HOST" || "$HOST" == "127.0.0.1" ]]; then
  echo "无法自动得到业务机 IP。请执行："
  echo "  sudo -E DELIVERY_HOST=10.x.x.x bash $0"
  exit 1
fi
echo "DELIVERY_HOST=$HOST"

echo "==> 导入离线 Docker 镜像（可能需要几分钟）"
docker load -i "$IMAGES"
docker image ls | grep -E 'postgres|temurin|caddy' || true

echo "==> 安装候选包到 $DEST"
if [[ -e "$DEST" ]]; then
  echo "目标目录已存在: $DEST"
  echo "若确认可删除旧安装，请先执行:  rm -rf $DEST"
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -qo "$CANDIDATE" -d "$TMP"
INSTALL_PY="$(find "$TMP" -name 'install-admin-release.py' | head -1)"
if [[ -z "$INSTALL_PY" ]]; then
  echo "候选包内找不到 scripts/install-admin-release.py"
  exit 1
fi
python3 "$INSTALL_PY" "$CANDIDATE" "$DEST"

echo "==> 一键启动（PostgreSQL + 管理端 + 自签 HTTPS）"
export DELIVERY_HOST="$HOST"
cd "$DEST"
python3 scripts/delivery-bootstrap.py

echo
echo "========================================"
echo "安装完成。"
echo "浏览器打开:  https://$HOST/"
echo "证书警告点「继续访问」。"
echo "默认账号见:  $DEST/config/BOOTSTRAP-ONCE.txt"
echo "务必备份:    $DEST/config/secrets.env  与  $DEST/config/tls/"
echo "========================================"
