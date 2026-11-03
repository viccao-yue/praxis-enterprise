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
DOCKER_TGZ="$ROOT/docker-static.tgz"
COMPOSE_BIN="$ROOT/docker-compose"
DOCKER_UNIT="$ROOT/docker.service"
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
if [[ ! -f "$DOCKER_TGZ" || ! -f "$COMPOSE_BIN" || ! -f "$DOCKER_UNIT" ]]; then
  echo "缺少 Docker 静态安装文件（docker-static.tgz / docker-compose / docker.service）"
  exit 1
fi

need_bin() {
  command -v "$1" >/dev/null 2>&1
}

echo "==> 检查 python3 / openssl（Ubuntu Server 默认自带）"
for bin in python3 openssl; do
  if ! need_bin "$bin"; then
    echo "缺少命令: $bin"
    echo "请使用 Ubuntu Server 22.04 标准安装（不要用极度裁剪的镜像）。"
    exit 1
  fi
done

install_static_docker() {
  echo "==> 安装包内 Docker（无需外网、无需预装）"
  local tmp
  tmp="$(mktemp -d)"
  tar -xzf "$DOCKER_TGZ" -C "$tmp"
  install -m 0755 "$tmp"/docker/* /usr/bin/
  rm -rf "$tmp"
  mkdir -p /usr/local/lib/docker/cli-plugins /usr/libexec/docker/cli-plugins /usr/lib/docker/cli-plugins
  install -m 0755 "$COMPOSE_BIN" /usr/local/lib/docker/cli-plugins/docker-compose
  install -m 0755 "$COMPOSE_BIN" /usr/libexec/docker/cli-plugins/docker-compose
  install -m 0755 "$COMPOSE_BIN" /usr/lib/docker/cli-plugins/docker-compose
  getent group docker >/dev/null || groupadd --system docker
  install -m 0644 "$DOCKER_UNIT" /etc/systemd/system/docker.service
  systemctl daemon-reload
  systemctl enable --now docker
  # dockerd 首次拉起需要几秒
  local i
  for i in $(seq 1 30); do
    if docker info >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Docker 已安装但没有启动成功。请执行: journalctl -u docker --no-pager | tail -50"
  exit 1
}

if ! need_bin docker || ! docker info >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  install_static_docker
fi
docker version
docker compose version

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
python3 - "$CANDIDATE" "$TMP" <<'PY'
import sys, zipfile
zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])
PY
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
