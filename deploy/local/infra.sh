#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
LOCAL_DIR="${ROOT_DIR}/.local"
DOWNLOAD_DIR="${RUNTIME_DIR}/downloads"
CONFIG_DIR="${ROOT_DIR}/deploy/local/config"

REDIS_VERSION="8.2.1"
KAFKA_VERSION="4.3.1"
KAFKA_SCALA_VERSION="2.13"
ELASTICSEARCH_VERSION="8.19.0"

POSTGRES_DB="${POSTGRES_DB:-seekflux}"
POSTGRES_USER="${POSTGRES_USER:-seekflux}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-seekflux_local}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-seekflux}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-seekflux_local_secret}"

REDIS_ARCHIVE="${DOWNLOAD_DIR}/redis-${REDIS_VERSION}.tar.gz"
KAFKA_ARCHIVE="${DOWNLOAD_DIR}/kafka_${KAFKA_SCALA_VERSION}-${KAFKA_VERSION}.tgz"
ELASTICSEARCH_ARCHIVE="${DOWNLOAD_DIR}/elasticsearch-${ELASTICSEARCH_VERSION}-linux-x86_64.tar.gz"

REDIS_HOME="${RUNTIME_DIR}/redis"
KAFKA_HOME="${RUNTIME_DIR}/kafka"
ELASTICSEARCH_HOME="${RUNTIME_DIR}/elasticsearch"
MINIO_HOME="${RUNTIME_DIR}/minio"

log() { printf '[SeekFlux] %s\n' "$*"; }
fail() { printf '[SeekFlux] ERROR: %s\n' "$*" >&2; exit 1; }

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1"
}

port_open() {
  timeout 1 bash -c "</dev/tcp/127.0.0.1/$1" >/dev/null 2>&1
}

wait_port() {
  local name="$1" port="$2" timeout_seconds="$3" elapsed=0
  while ! port_open "${port}"; do
    if (( elapsed >= timeout_seconds )); then
      fail "${name} 未在 ${timeout_seconds}s 内监听 127.0.0.1:${port}"
    fi
    sleep 1
    ((elapsed += 1))
  done
  log "${name} 已就绪 (127.0.0.1:${port})"
}

download_file() {
  local url="$1" destination="$2"
  require_command curl
  mkdir -p "$(dirname "${destination}")"
  log "下载/断点续传 $(basename "${destination}")"
  curl -fL --retry 8 --retry-delay 3 -C - -o "${destination}" "${url}"
}

extract_archive() {
  local archive="$1" expected_dir="$2" destination="$3" temp_dir
  temp_dir="$(mktemp -d "${RUNTIME_DIR}/extract.XXXXXX")"
  if ! tar -xzf "${archive}" -C "${temp_dir}"; then
    rm -rf "${temp_dir}"
    fail "归档损坏或未下载完整: ${archive}；重新执行脚本会继续下载"
  fi
  rm -rf "${destination}"
  mv "${temp_dir}/${expected_dir}" "${destination}"
  rmdir "${temp_dir}"
}

ensure_redis() {
  if [[ ! -d "${REDIS_HOME}" ]]; then
    download_file "https://download.redis.io/releases/redis-${REDIS_VERSION}.tar.gz" "${REDIS_ARCHIVE}"
  fi
  if [[ ! -d "${REDIS_HOME}" ]]; then
    log "解压 Redis ${REDIS_VERSION}"
    extract_archive "${REDIS_ARCHIVE}" "redis-${REDIS_VERSION}" "${REDIS_HOME}"
  fi
  if [[ ! -x "${REDIS_HOME}/src/redis-server" || ! -x "${REDIS_HOME}/src/redis-cli" ]]; then
    require_command make
    log "编译 Redis ${REDIS_VERSION}（使用系统 libc）"
    make -C "${REDIS_HOME}" -j"$(nproc)" MALLOC=libc BUILD_TLS=no
  fi
}

ensure_kafka() {
  if [[ ! -x "${KAFKA_HOME}/bin/kafka-server-start.sh" ]]; then
    download_file "https://downloads.apache.org/kafka/${KAFKA_VERSION}/kafka_${KAFKA_SCALA_VERSION}-${KAFKA_VERSION}.tgz" "${KAFKA_ARCHIVE}"
  fi
  if [[ ! -x "${KAFKA_HOME}/bin/kafka-server-start.sh" ]]; then
    log "解压 Kafka ${KAFKA_VERSION}"
    extract_archive "${KAFKA_ARCHIVE}" "kafka_${KAFKA_SCALA_VERSION}-${KAFKA_VERSION}" "${KAFKA_HOME}"
  fi
}

ensure_elasticsearch() {
  if [[ ! -x "${ELASTICSEARCH_HOME}/bin/elasticsearch" ]]; then
    download_file "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-${ELASTICSEARCH_VERSION}-linux-x86_64.tar.gz" "${ELASTICSEARCH_ARCHIVE}"
  fi
  if [[ ! -x "${ELASTICSEARCH_HOME}/bin/elasticsearch" ]]; then
    log "解压 Elasticsearch ${ELASTICSEARCH_VERSION}"
    extract_archive "${ELASTICSEARCH_ARCHIVE}" "elasticsearch-${ELASTICSEARCH_VERSION}" "${ELASTICSEARCH_HOME}"
  fi
}

ensure_minio() {
  mkdir -p "${MINIO_HOME}"
  if [[ ! -x "${MINIO_HOME}/minio" ]]; then
    download_file "https://dl.min.io/server/minio/release/linux-amd64/minio" "${MINIO_HOME}/minio"
    chmod +x "${MINIO_HOME}/minio"
  fi
  if [[ ! -x "${MINIO_HOME}/mc" ]]; then
    download_file "https://dl.min.io/client/mc/release/linux-amd64/mc" "${MINIO_HOME}/mc"
    chmod +x "${MINIO_HOME}/mc"
  fi
}

download_all() {
  mkdir -p "${RUNTIME_DIR}" "${DOWNLOAD_DIR}"
  ensure_redis
  ensure_kafka
  ensure_elasticsearch
  ensure_minio
  log "Redis、Kafka、Elasticsearch、MinIO 均已下载并安装到 ${RUNTIME_DIR}"
}

prepare_directories() {
  mkdir -p "${CONFIG_DIR}"
  for component in redis kafka elasticsearch minio; do
    mkdir -p "${LOCAL_DIR}/${component}"/{data,logs,run}
  done
}

start_postgres() {
  require_command pg_isready
  if ! pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1; then
    require_command pg_ctlcluster
    local cluster_info pg_version pg_name pg_data pg_log
    cluster_info="$(pg_lsclusters --no-header | awk '$3 == 5432 {print; exit}')"
    [[ -n "${cluster_info}" ]] || fail "没有找到监听端口 5432 的 PostgreSQL cluster"
    read -r pg_version pg_name _ _ _ pg_data pg_log <<<"${cluster_info}"

    # 某些临时开发环境会以 root 初始化 cluster；PostgreSQL 自身拒绝以 root 运行。
    if [[ "$(stat -c %U "${pg_data}")" == root && "${EUID}" -eq 0 ]]; then
      chown -R postgres:postgres "${pg_data}"
      touch "${pg_log}"
      chown postgres:postgres "${pg_log}"
    fi
    if [[ "${EUID}" -eq 0 ]] && getent group ssl-cert >/dev/null 2>&1 \
      && ! id -nG postgres | tr ' ' '\n' | grep -qx ssl-cert; then
      usermod -aG ssl-cert postgres
    fi
    if [[ "${EUID}" -eq 0 && -f /etc/ssl/private/ssl-cert-snakeoil.key ]]; then
      chgrp ssl-cert /etc/ssl/private /etc/ssl/private/ssl-cert-snakeoil.key
      chmod 0710 /etc/ssl/private
      chmod 0640 /etc/ssl/private/ssl-cert-snakeoil.key
    fi
    if [[ "${EUID}" -eq 0 && -d /var/run/postgresql ]]; then
      chown postgres:postgres /var/run/postgresql
      chmod 2775 /var/run/postgresql
      rm -f /var/run/postgresql/.s.PGSQL.5432 /var/run/postgresql/.s.PGSQL.5432.lock
      chown -R postgres:postgres /var/run/postgresql/12-main.pg_stat_tmp 2>/dev/null || true
      rm -f /var/run/postgresql/12-main.pid
    fi
    if [[ "${EUID}" -eq 0 ]]; then
      chgrp postgres "/etc/postgresql/${pg_version}/${pg_name}/pg_hba.conf" \
        "/etc/postgresql/${pg_version}/${pg_name}/pg_ident.conf"
      chmod 0640 "/etc/postgresql/${pg_version}/${pg_name}/pg_hba.conf" \
        "/etc/postgresql/${pg_version}/${pg_name}/pg_ident.conf"
    fi
    pg_ctlcluster "${pg_version}" "${pg_name}" start
  fi
  wait_port PostgreSQL 5432 30

  local psql_as_admin=(psql -v ON_ERROR_STOP=1 -d postgres)
  if [[ "${EUID}" -eq 0 ]]; then
    psql_as_admin=(runuser -u postgres -- psql -v ON_ERROR_STOP=1 -d postgres)
  fi
  "${psql_as_admin[@]}" -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${POSTGRES_USER}') THEN CREATE ROLE ${POSTGRES_USER} LOGIN PASSWORD '${POSTGRES_PASSWORD}'; ELSE ALTER ROLE ${POSTGRES_USER} WITH LOGIN PASSWORD '${POSTGRES_PASSWORD}'; END IF; END \$\$;" >/dev/null
  if ! "${psql_as_admin[@]}" -Atc "SELECT 1 FROM pg_database WHERE datname='${POSTGRES_DB}'" | grep -qx 1; then
    if [[ "${EUID}" -eq 0 ]]; then
      runuser -u postgres -- createdb -O "${POSTGRES_USER}" "${POSTGRES_DB}"
    else
      createdb -O "${POSTGRES_USER}" "${POSTGRES_DB}"
    fi
  fi
  local schema_sql
  schema_sql="CREATE SCHEMA IF NOT EXISTS content AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS feature_registry AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS experiment AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS model_registry AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS outbox AUTHORIZATION ${POSTGRES_USER};"
  if [[ "${EUID}" -eq 0 ]]; then
    runuser -u postgres -- psql -v ON_ERROR_STOP=1 -d "${POSTGRES_DB}" -c "${schema_sql}" >/dev/null
  else
    psql -v ON_ERROR_STOP=1 -d "${POSTGRES_DB}" -c "${schema_sql}" >/dev/null
  fi
}

start_redis() {
  if "${REDIS_HOME}/src/redis-cli" -h 127.0.0.1 -p 6379 ping 2>/dev/null | grep -qx PONG; then
    log "Redis 已在运行"
    return
  fi
  cat >"${CONFIG_DIR}/redis.generated.conf" <<EOF
bind 127.0.0.1
port 6379
protected-mode yes
daemonize yes
dir ${LOCAL_DIR}/redis/data
pidfile ${LOCAL_DIR}/redis/run/redis.pid
logfile ${LOCAL_DIR}/redis/logs/redis.log
appendonly yes
appenddirname appendonlydir
maxmemory-policy allkeys-lru
EOF
  rm -f "${LOCAL_DIR}/redis/run/redis.pid"
  "${REDIS_HOME}/src/redis-server" "${CONFIG_DIR}/redis.generated.conf"
  wait_port Redis 6379 30
}

write_kafka_config() {
  cat >"${CONFIG_DIR}/kafka.generated.properties" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9093
controller.listener.names=CONTROLLER
listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093
advertised.listeners=PLAINTEXT://127.0.0.1:9092
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
inter.broker.listener.name=PLAINTEXT
log.dirs=${LOCAL_DIR}/kafka/data
num.partitions=3
default.replication.factor=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
auto.create.topics.enable=false
EOF
}

create_kafka_topics() {
  local topic_initializer="${LOCAL_DIR}/kafka/run/CreateSeekFluxTopics.java"
  cat >"${topic_initializer}" <<'EOF'
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;

class CreateSeekFluxTopics {
  public static void main(String[] args) throws Exception {
    List<String> names = List.of(
      "interaction.raw.v1", "interaction.validated.v1", "exposure.logged.v1",
      "content.profile.published.v1", "content.distribution.changed.v1",
      "feature.snapshot.updated.v1", "model.version.activated.v1"
    );
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:9092"))) {
      Set<String> existing = admin.listTopics().names().get(60, TimeUnit.SECONDS);
      List<NewTopic> missing = names.stream().filter(name -> !existing.contains(name))
        .map(name -> new NewTopic(name, 3, (short) 1)).toList();
      if (!missing.isEmpty()) admin.createTopics(missing).all().get(60, TimeUnit.SECONDS);
    }
  }
}
EOF
  timeout 120 java -cp "${KAFKA_HOME}/libs/*" "${topic_initializer}"
  log "Kafka 业务 Topics 已初始化"
}

start_kafka() {
  if port_open 9092; then
    log "Kafka 已在运行"
    return
  fi
  write_kafka_config
  if [[ ! -f "${LOCAL_DIR}/kafka/data/meta.properties" ]]; then
    log "格式化 Kafka KRaft 存储"
    local cluster_id
    cluster_id="$("${KAFKA_HOME}/bin/kafka-storage.sh" random-uuid)"
    "${KAFKA_HOME}/bin/kafka-storage.sh" format --standalone -t "${cluster_id}" -c "${CONFIG_DIR}/kafka.generated.properties" >/dev/null
  fi
  rm -f "${LOCAL_DIR}/kafka/run/kafka.pid"
  nohup setsid env KAFKA_HEAP_OPTS="-Xms256m -Xmx512m" \
    KAFKA_LOG4J_OPTS="-Dkafka.logs.dir=${LOCAL_DIR}/kafka/logs" \
    "${KAFKA_HOME}/bin/kafka-server-start.sh" "${CONFIG_DIR}/kafka.generated.properties" \
    >"${LOCAL_DIR}/kafka/logs/server.out" 2>&1 </dev/null &
  printf '%s\n' "$!" >"${LOCAL_DIR}/kafka/run/kafka.pid"
  wait_port Kafka 9092 180
  create_kafka_topics
}

start_elasticsearch() {
  if curl --noproxy '*' -fsS http://127.0.0.1:9200/ >/dev/null 2>&1; then
    log "Elasticsearch 已在运行"
    return
  fi
  cat >"${ELASTICSEARCH_HOME}/config/elasticsearch.yml" <<EOF
cluster.name: seekflux-local
node.name: seekflux-node-1
path.data: ${LOCAL_DIR}/elasticsearch/data
path.logs: ${LOCAL_DIR}/elasticsearch/logs
network.host: 127.0.0.1
http.port: 9200
discovery.type: single-node
xpack.security.enabled: false
xpack.security.enrollment.enabled: false
xpack.ml.enabled: false
xpack.watcher.enabled: false
ingest.geoip.downloader.enabled: false
EOF
  mkdir -p "${ELASTICSEARCH_HOME}/config/jvm.options.d"
  cat >"${ELASTICSEARCH_HOME}/config/jvm.options.d/seekflux.options" <<EOF
-Xms512m
-Xmx512m
EOF
  rm -f "${LOCAL_DIR}/elasticsearch/run/elasticsearch.pid"
  if [[ "${EUID}" -eq 0 ]]; then
    if ! id seekflux-es >/dev/null 2>&1; then
      useradd --system --home-dir "${LOCAL_DIR}/elasticsearch" --shell /usr/sbin/nologin seekflux-es
    fi
    # Elasticsearch 必须以非 root 用户运行；项目位于 /root 时仅授予目录穿越权限。
    if [[ "${ROOT_DIR}" == /root/* ]] && ! runuser -u seekflux-es -- test -x "${ROOT_DIR}"; then
      chmod 0711 /root
    fi
    chmod 1777 /tmp /var/tmp
    chown -R seekflux-es:nogroup "${LOCAL_DIR}/elasticsearch" "${ELASTICSEARCH_HOME}"
    nohup runuser -u seekflux-es -- setsid "${ELASTICSEARCH_HOME}/bin/elasticsearch" \
      -p "${LOCAL_DIR}/elasticsearch/run/elasticsearch.pid" \
      >"${LOCAL_DIR}/elasticsearch/logs/console.log" 2>&1 </dev/null &
  else
    nohup setsid "${ELASTICSEARCH_HOME}/bin/elasticsearch" \
      -p "${LOCAL_DIR}/elasticsearch/run/elasticsearch.pid" \
      >"${LOCAL_DIR}/elasticsearch/logs/console.log" 2>&1 </dev/null &
  fi
  wait_port Elasticsearch 9200 300
}

start_minio() {
  if curl --noproxy '*' -fsS http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1; then
    log "MinIO 已在运行"
    return
  fi
  rm -f "${LOCAL_DIR}/minio/run/minio.pid"
  MINIO_ROOT_USER="${MINIO_ROOT_USER}" MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
    nohup setsid "${MINIO_HOME}/minio" server "${LOCAL_DIR}/minio/data" \
      --address 127.0.0.1:9000 --console-address 127.0.0.1:9002 \
      >"${LOCAL_DIR}/minio/logs/minio.log" 2>&1 </dev/null &
  printf '%s\n' "$!" >"${LOCAL_DIR}/minio/run/minio.pid"
  wait_port MinIO 9000 60
  MC_CONFIG_DIR="${LOCAL_DIR}/minio/mc-config" "${MINIO_HOME}/mc" alias set seekflux-local \
    http://127.0.0.1:9000 "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null
  MC_CONFIG_DIR="${LOCAL_DIR}/minio/mc-config" "${MINIO_HOME}/mc" mb --ignore-existing \
    seekflux-local/seekflux-artifacts seekflux-local/seekflux-checkpoints >/dev/null
  log "MinIO Buckets 已初始化；控制台 http://127.0.0.1:9002"
}

status_all() {
  local failed=0
  if pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1; then log "PostgreSQL: UP :5432"; else log "PostgreSQL: DOWN"; failed=1; fi
  if "${REDIS_HOME}/src/redis-cli" -h 127.0.0.1 -p 6379 ping 2>/dev/null | grep -qx PONG; then log "Redis: UP :6379"; else log "Redis: DOWN"; failed=1; fi
  if port_open 9092; then log "Kafka: UP :9092"; else log "Kafka: DOWN"; failed=1; fi
  if curl --noproxy '*' -fsS http://127.0.0.1:9200/ >/dev/null 2>&1; then log "Elasticsearch: UP :9200"; else log "Elasticsearch: DOWN"; failed=1; fi
  if curl --noproxy '*' -fsS http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1; then log "MinIO: UP :9000 (Console :9002)"; else log "MinIO: DOWN"; failed=1; fi
  return "${failed}"
}

stop_pidfile() {
  local name="$1" pidfile="$2" pid
  [[ -f "${pidfile}" ]] || return 0
  pid="$(cat "${pidfile}")"
  if [[ "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}"
    for _ in {1..30}; do kill -0 "${pid}" 2>/dev/null || break; sleep 1; done
    kill -0 "${pid}" 2>/dev/null && kill -KILL "${pid}" 2>/dev/null || true
    log "${name} 已停止"
  fi
  rm -f "${pidfile}"
}

stop_all() {
  stop_pidfile MinIO "${LOCAL_DIR}/minio/run/minio.pid"
  stop_pidfile Elasticsearch "${LOCAL_DIR}/elasticsearch/run/elasticsearch.pid"
  stop_pidfile Kafka "${LOCAL_DIR}/kafka/run/kafka.pid"
  if [[ -x "${REDIS_HOME}/src/redis-cli" ]]; then
    "${REDIS_HOME}/src/redis-cli" -h 127.0.0.1 -p 6379 shutdown >/dev/null 2>&1 || true
  fi
  if command -v pg_lsclusters >/dev/null 2>&1 && pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1; then
    local cluster_info pg_version pg_name
    cluster_info="$(pg_lsclusters --no-header | awk '$3 == 5432 {print; exit}')"
    read -r pg_version pg_name _ <<<"${cluster_info}"
    [[ -n "${pg_version:-}" ]] && pg_ctlcluster "${pg_version}" "${pg_name}" stop
  fi
  log "SeekFlux 本地中间件已停止"
}

start_all() {
  require_command timeout
  require_command curl
  prepare_directories
  download_all
  start_postgres
  start_redis
  start_kafka
  start_elasticsearch
  start_minio
  status_all
}

usage() {
  echo "用法: $0 {start|download|status|stop|restart}"
}

case "${1:-start}" in
  start) start_all ;;
  download) download_all ;;
  status) status_all ;;
  stop) stop_all ;;
  restart) stop_all; start_all ;;
  *) usage; exit 2 ;;
esac
