#!/usr/bin/env python3
"""In-place offline upgrade of the stock Compose delivery from run 42150375552."""
import argparse
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import zipfile


COMPOSE = Path("deploy/docker-compose.delivery.yml")
JAR = Path("server/kaiwu-praxis-admin-server.jar")
WEB = Path("web/dist")
MIGRATIONS = Path("deploy/postgres/migrations")
V010 = MIGRATIONS / "V010__desktop_session_renewal.sql"
SQL = Path("config/schema-migrate.sql")
PROGRAM = (JAR, WEB, V010, SQL, Path("scripts/delivery-bootstrap.py"))
OLD_VERSIONS = {1, 2, 3, 6, 7, 8, 9}
BACKUPS = ".praxis-upgrade-backups"
HERE = Path(__file__).resolve().parent
ARCHIVE = "kaiwu-praxis-admin-candidate.zip"
ARCHIVE_SHA256 = "125ab3e2dedd6a23e7c7534e252ae3ac516f47e11a2e90f06c69c05436a1f068"
CADDY_PORTS = b'    ports:\n      - "443:443"\n      - "80:80"\n'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def check_compose_ports(installed, standard):
    """Accept only numeric Caddy host-port changes; compare everything else byte for byte."""
    require(standard.count(CADDY_PORTS) == 1, "候选包 Caddy 端口模板不匹配")
    before, after = standard.split(CADDY_PORTS)
    require(installed.startswith(before) and installed.endswith(after),
            "现场 Compose 除 Caddy 宿主机端口外还有差异，请人工核对")
    ports = re.fullmatch(rb'    ports:\n      - "([1-9][0-9]{0,4}):443"\n'
                         rb'      - "([1-9][0-9]{0,4}):80"\n',
                         installed[len(before):len(installed) - len(after)])
    require(ports is not None, "仅支持修改 Caddy 宿主机端口，容器端口必须保持 443 和 80")
    https, http = map(int, ports.groups())
    require(https <= 65535 and http <= 65535 and https != http, "Caddy 宿主机端口无效或重复")
    return https, http


def sha256(path):
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def install_file(source, dest):
    """Replace regular files atomically; recreate the bind-mounted service after copying."""
    with tempfile.TemporaryDirectory(prefix=".praxis-swap-", dir=dest.parent) as stage:
        temp = Path(stage) / dest.name
        if source.exists():
            if source.is_dir():
                shutil.copytree(source, temp)
            else:
                shutil.copy2(source, temp)
        if dest.is_dir():
            dest.rename(Path(stage) / "previous")
        elif dest.exists() and not source.exists():
            dest.unlink()
        if temp.exists():
            temp.replace(dest)


class Upgrade:
    def __init__(self, root, project):
        self.root = root
        self.compose = [
            "docker", "compose", "--project-name", project,
            "--project-directory", str(root), "--env-file", str(root / ".env"),
            "-f", str(root / COMPOSE),
        ]

    def run(self, *args, **kwargs):
        return subprocess.run([*self.compose, *args], cwd=self.root, check=True, **kwargs)

    def output(self, *args):
        return self.run(*args, stdout=subprocess.PIPE, text=True).stdout.strip()

    def sql(self, query):
        return self.output("exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
                           "-U", "praxis_maint", "-d", "kaiwu_praxis", "-At", "-c", query)

    def caddy_host_port(self, target):
        bindings = self.output("port", "caddy", str(target)).splitlines()
        local = [address.rsplit(":", 1)[1] for address in bindings
                 if address.rsplit(":", 1)[0] in ("127.0.0.1", "0.0.0.0")]
        require(len(local) == 1, f"无法确定 Caddy 的本地端口映射（容器端口 {target}）")
        return int(local[0])

    def check_stack(self, recovering=False, expected_ports=None):
        files = (COMPOSE, Path(".env"), Path("config/secrets.env"),
                 Path("config/tls/cert.pem"), Path("config/tls/key.pem"))
        if not recovering:
            files += (JAR, WEB / "index.html", SQL, Path("scripts/delivery-bootstrap.py"))
        for file in files:
            require((self.root / file).is_file(), f"找不到原部署文件：{file}")
        for service, binds, volume in (
            ("admin", {"/opt/workdsh/server.jar": JAR, "/opt/workdsh/static": WEB}, None),
            ("caddy", {"/etc/caddy/tls": Path("config/tls")}, None),
            ("postgres", {}, "/var/lib/postgresql/data"),
        ):
            recovering_admin = recovering and service == "admin"
            scope = ("--all",) if recovering_admin else ("--status", "running")
            ids = self.output("ps", *scope, "-q", service).splitlines()
            if recovering_admin and not ids:
                continue
            require(len(ids) == 1, f"未找到唯一运行中的 {service}，请核对项目名和目录")
            result = subprocess.check_output(
                ["docker", "inspect", "--format", "{{json .Mounts}}", ids[0]], text=True)
            mounts = {m["Destination"].rstrip("/"): m for m in json.loads(result)}
            for target, path in binds.items():
                mount = mounts.get(target, {})
                require(mount.get("Type") == "bind"
                        and Path(mount["Source"]).resolve() == (self.root / path).resolve(),
                        f"{service} 的实际挂载不属于此安装目录：{target}")
            if volume:
                require(mounts.get(volume, {}).get("Type") == "volume",
                        "PostgreSQL 不是标准命名数据卷部署")
        self.port = self.caddy_host_port(443)
        if expected_ports is not None:
            actual = self.port, self.caddy_host_port(80)
            require(actual == expected_ports,
                    f"Caddy 实际 HTTPS/HTTP 端口 {actual} 与现场 Compose 的 {expected_ports} 不一致")
        self.tls = ssl.create_default_context(cafile=str(self.root / "config/tls/cert.pem"))

    def check_schema(self, candidate):
        expected = {}
        for file in (candidate / MIGRATIONS).glob("V[0-9][0-9][0-9]__*.sql"):
            expected[int(file.name[1:4])] = f"{file.name}|{sha256(file)}"
        require(set(expected) == OLD_VERSIONS | {10}, "升级包迁移清单不匹配")
        require(self.sql("select to_regclass('public.workdsh_schema_history') is not null") == "t",
                "没有迁移历史；不能自动接管数据库")
        actual = {}
        for row in self.sql(
            "select version,filename,sha256 from public.workdsh_schema_history order by version"
        ).splitlines():
            version, info = row.split("|", 1)
            actual[int(version)] = info
        require(OLD_VERSIONS <= actual.keys()
                and all(expected.get(version) == info for version, info in actual.items()),
                "原库迁移版本或校验和不匹配；没有执行迁移")
        return 10 in actual

    def status(self, method, path):
        # The original delivery certificate always includes DNS:localhost.
        conn = http.client.HTTPSConnection("localhost", self.port, timeout=4, context=self.tls)
        try:
            conn.request(method, path)
            response = conn.getresponse()
            return response.status, response.getheader("Cache-Control", "")
        finally:
            conn.close()

    def ready(self, renewal=False, seconds=100):
        until = time.monotonic() + seconds
        while True:
            try:
                if (self.status("GET", "/")[0] == 200
                        and self.status("GET", "/api/auth/me")[0] == 401
                        and (not renewal or self.status("POST", "/api/auth/desktop/renew")
                             == (401, "no-store"))):
                    return
            except (OSError, http.client.HTTPException):
                pass
            require(time.monotonic() < until, "HTTPS、登录探测或续期接口验收超时")
            time.sleep(2)

    def backup(self):
        folder = self.root / BACKUPS
        folder.mkdir(mode=0o700, exist_ok=True)
        snapshot = Path(tempfile.mkdtemp(prefix="before-", dir=folder))
        print(f"备份目录：{snapshot}", flush=True)
        files = snapshot / "files"
        for item in PROGRAM:
            source = self.root / item
            dest = files / item
            dest.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, dest)
            elif source.exists():
                shutil.copy2(source, dest)
        shutil.copytree(self.root / "config", snapshot / "config")
        shutil.copy2(self.root / ".env", snapshot / ".env")
        with (snapshot / "database.dump").open("wb") as stream:
            self.run("exec", "-T", "postgres", "pg_dump", "-U", "praxis_maint",
                     "-d", "kaiwu_praxis", "-Fc", stdout=stream)
        with (snapshot / "database.dump").open("rb") as stream:
            self.run("exec", "-T", "postgres", "pg_restore", "-f", "/dev/null", stdin=stream,
                     stdout=subprocess.DEVNULL)
        (snapshot / "ready").touch()
        return snapshot

    def recreate_admin(self):
        self.run("up", "-d", "--no-deps", "--no-build", "--pull", "never",
                 "--force-recreate", "admin")

    def switch(self, files):
        self.run("stop", "admin")
        for item in PROGRAM:
            install_file(files / item, self.root / item)
        self.recreate_admin()

    def rollback(self, snapshot):
        require((snapshot / "ready").is_file(), "备份未完成，禁止自动回退")
        self.switch(snapshot / "files")
        self.ready()

    def apply(self, candidate):
        snapshot = self.backup()
        (candidate / SQL).parent.mkdir()
        with (candidate / SQL).open("wb") as stream:
            subprocess.run([sys.executable, str(candidate / "scripts/migrate-postgres.py"),
                            "--emit-sql"], check=True, stdout=stream)
        with (candidate / SQL).open("rb") as stream:
            self.run("exec", "-T", "-e", "PGOPTIONS=-c lock_timeout=15s",
                     "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
                     "-U", "praxis_maint", "-d", "kaiwu_praxis", stdin=stream,
                     stdout=subprocess.DEVNULL)
        require(self.check_schema(candidate), "迁移结束但未找到 V010")
        try:
            self.switch(candidate)
            self.ready(renewal=True)
        except (Exception, KeyboardInterrupt):
            print("新版未通过验收，正在切回旧版程序。", file=sys.stderr, flush=True)
            try:
                self.rollback(snapshot)
            except (Exception, KeyboardInterrupt) as error:
                raise RuntimeError(f"自动回退失败；请保留备份 {snapshot}：{error}") from error
            print("已恢复旧版程序；保留 V010 和现场数据。", file=sys.stderr)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path, default=Path("/opt/kaiwu-praxis-admin"))
    parser.add_argument("--project", default="kaiwu-praxis-delivery")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--apply", action="store_true")
    action.add_argument("--rollback", metavar="BACKUP_NAME")
    args = parser.parse_args()
    root = args.install_dir.resolve(strict=True)
    os.umask(0o077)
    upgrade = Upgrade(root, args.project)
    # No write to the installation on default preflight.
    lock = ((root / ".praxis-upgrade.lock").open("a")
            if args.apply or args.rollback else open(os.devnull))
    with lock, tempfile.TemporaryDirectory(prefix="praxis-upgrade-") as temp:
        if args.apply or args.rollback:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        candidate = Path(temp) / "candidate"
        archive = HERE / ARCHIVE
        checksum = (HERE / (ARCHIVE + ".sha256")).read_text().split()[0]
        require(checksum == ARCHIVE_SHA256 == sha256(archive), "候选包 SHA-256 不匹配")
        installer = Path(temp) / "install-admin-release.py"
        with zipfile.ZipFile(archive) as zipped:
            installer.write_bytes(zipped.read("scripts/install-admin-release.py"))
        subprocess.run([sys.executable, str(installer),
                        str(archive), str(candidate)], check=True, stdout=subprocess.DEVNULL)
        # Generated ZIP has no directory modes; the container must be able to read static assets.
        for directory in (candidate / WEB, *(p for p in (candidate / WEB).rglob("*") if p.is_dir())):
            directory.chmod(0o755)
        https, http = check_compose_ports((root / COMPOSE).read_bytes(),
                                         (candidate / COMPOSE).read_bytes())
        upgrade.check_stack(recovering=bool(args.rollback), expected_ports=(https, http))
        print(f"保留现有 Caddy 入口：HTTPS {https}，HTTP {http}。", flush=True)
        already_migrated = upgrade.check_schema(candidate)
        if args.rollback:
            require(Path(args.rollback).name == args.rollback and args.rollback.startswith("before-"),
                    "请输入脚本打印的备份目录名（before-...）")
            upgrade.rollback(root / BACKUPS / args.rollback)
            print("旧版控制台已恢复；未回写数据库，保留 V010 和现场数据。")
            return
        upgrade.ready(seconds=10)
        if already_migrated and sha256(root / JAR) == sha256(candidate / JAR):
            upgrade.ready(renewal=True, seconds=10)
            print("已运行 v3.1.1，无需重复升级。")
            return
        if not args.apply:
            print("预检通过，未改库、未停服。安排维护窗口后追加 --apply。")
            return
        upgrade.apply(candidate)
        print("v3.1.1 升级成功。请用新版桌面端重新登录并验证实际续期。")


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        sys.exit(f"操作未完成：{error}")
