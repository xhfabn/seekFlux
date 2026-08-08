#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
LOCAL_DIR="${ROOT_DIR}/.local"
DOWNLOAD_DIR="${RUNTIME_DIR}/downloads"
CONFIG_DIR="${LOCAL_DIR}/config"
VERSION_FILE="${ROOT_DIR}/deploy/local/versions.env"
ENV_FILE="${ROOT_DIR}/.env"

[[ -f "${VERSION_FILE}" ]] || { printf '缺少版本清单: %s\n' "${VERSION_FILE}" >&2; exit 1; }
# shellcheck disable=SC1090
source "${VERSION_FILE}"

OS_NAME="$(uname -s)"
CPU_ARCH="$(uname -m)"
case "${OS_NAME}:${CPU_ARCH}" in
  Darwin:arm64)
    ES_PLATFORM="darwin-aarch64"
    MINIO_PLATFORM="darwin-arm64"
    ;;
  Darwin:x86_64)
    ES_PLATFORM="darwin-x86_64"
    MINIO_PLATFORM="darwin-amd64"
    ;;
  Linux:aarch64|Linux:arm64)
    ES_PLATFORM="linux-aarch64"
    MINIO_PLATFORM="linux-arm64"
    ;;
  Linux:x86_64)
    ES_PLATFORM="linux-x86_64"
    MINIO_PLATFORM="linux-amd64"
    ;;
  *)
    printf '[SeekFlux] ERROR: 暂不支持的平台 %s/%s\n' "${OS_NAME}" "${CPU_ARCH}" >&2
    exit 1
    ;;
esac

REDIS_HOME="${RUNTIME_DIR}/redis-${REDIS_VERSION}"
KAFKA_HOME="${RUNTIME_DIR}/kafka_${KAFKA_SCALA_VERSION}-${KAFKA_VERSION}"
ELASTICSEARCH_HOME="${RUNTIME_DIR}/elasticsearch-${ELASTICSEARCH_VERSION}"
MINIO_HOME="${RUNTIME_DIR}/minio-${MINIO_VERSION}"
POSTGRES_DATA="${LOCAL_DIR}/postgres/data"

log() { printf '[SeekFlux] %s\n' "$*"; }
warn() { printf '[SeekFlux] WARN: %s\n' "$*" >&2; }
fail() { printf '[SeekFlux] ERROR: %s\n' "$*" >&2; exit 1; }

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1"
}

cpu_count() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    sysctl -n hw.ncpu
  else
    getconf _NPROCESSORS_ONLN 2>/dev/null || printf '2\n'
  fi
}

prepare_directories() {
  mkdir -p "${RUNTIME_DIR}" "${DOWNLOAD_DIR}" "${CONFIG_DIR}"
  local component
  for component in postgres redis kafka elasticsearch minio apps; do
    mkdir -p "${LOCAL_DIR}/${component}/data" "${LOCAL_DIR}/${component}/logs" "${LOCAL_DIR}/${component}/run"
  done
}

load_local_env() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    cp "${ROOT_DIR}/.env.example" "${ENV_FILE}"
    log "已从 .env.example 创建本地 .env"
  fi
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a

  POSTGRES_DB="${POSTGRES_DB:-seekflux}"
  POSTGRES_USER="${POSTGRES_USER:-seekflux}"
  POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-seekflux_local}"
  POSTGRES_PORT="${POSTGRES_PORT:-5432}"
  REDIS_PORT="${REDIS_PORT:-6379}"
  KAFKA_PORT="${KAFKA_PORT:-9092}"
  ELASTICSEARCH_PORT="${ELASTICSEARCH_PORT:-9200}"
  MINIO_API_PORT="${MINIO_API_PORT:-9000}"
  MINIO_CONSOLE_PORT="${MINIO_CONSOLE_PORT:-9002}"
  MINIO_ROOT_USER="${MINIO_ROOT_USER:-seekflux}"
  MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-seekflux_local_secret}"
  ONLINE_SERVER_PORT="${ONLINE_SERVER_PORT:-8080}"
  CONTENT_SERVER_PORT="${CONTENT_SERVER_PORT:-8081}"
  AGENT_SERVER_PORT="${AGENT_SERVER_PORT:-8083}"
  WEB_SERVER_PORT="${WEB_SERVER_PORT:-3001}"
}

configure_java() {
  if [[ "${OS_NAME}" == "Darwin" && -x /usr/libexec/java_home ]]; then
    local java_21
    java_21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "${java_21}" ]]; then
      export JAVA_HOME="${java_21}"
      export PATH="${JAVA_HOME}/bin:${PATH}"
    fi
  fi
  require_command java
  local java_major
  java_major="$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
  [[ "${java_major}" =~ ^[0-9]+$ ]] || fail "无法识别 Java 版本"
  (( java_major >= 21 )) || fail "需要 JDK 21+，当前为 ${java_major}"
}

port_open() {
  nc -z 127.0.0.1 "$1" >/dev/null 2>&1
}

wait_port() {
  local name="$1" port="$2" timeout_seconds="$3" elapsed=0
  while ! port_open "${port}"; do
    if (( elapsed >= timeout_seconds )); then
      fail "${name} 未在 ${timeout_seconds}s 内监听 127.0.0.1:${port}"
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  log "${name} 已就绪 (127.0.0.1:${port})"
}

wait_http() {
  local name="$1" url="$2" timeout_seconds="$3" elapsed=0
  while ! curl --noproxy '*' -fsS "${url}" >/dev/null 2>&1; do
    if (( elapsed >= timeout_seconds )); then
      fail "${name} 未在 ${timeout_seconds}s 内通过健康检查: ${url}"
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  log "${name} 健康检查通过"
}

download_file() {
  local destination="$1"
  shift
  [[ -f "${destination}" ]] && return 0
  local partial="${destination}.part" url
  mkdir -p "$(dirname "${destination}")"
  for url in "$@"; do
    log "下载 $(basename "${destination}")"
    if curl -fL --retry 6 --retry-delay 2 -C - -o "${partial}" "${url}"; then
      mv "${partial}" "${destination}"
      return 0
    fi
    warn "下载源不可用，尝试下一个地址: ${url}"
  done
  fail "无法下载 $(basename "${destination}")"
}

download_file_parallel() {
  local destination="$1" url="$2" segments="${3:-8}"
  [[ -f "${destination}" ]] && return 0

  local -a curl_route=()
  # 显式配置的本地代理优先；不可用时自动回退，不让代理状态阻塞安装。
  if [[ "${url}" != https://gh-proxy.com/* && "${url}" != https://ghfast.top/* \
    && -n "${SEEKFLUX_DOWNLOAD_PROXY:-}" ]] \
    && curl -x "${SEEKFLUX_DOWNLOAD_PROXY}" -fsSIL --connect-timeout 5 --max-time 10 "${url}" >/dev/null 2>&1; then
    curl_route=(-x "${SEEKFLUX_DOWNLOAD_PROXY}")
    log "使用下载代理 ${SEEKFLUX_DOWNLOAD_PROXY}"
  # 在部分中国大陆网络中，Elastic/MinIO CDN 的 IPv6 链路明显快于 IPv4。
  elif curl -6 -fsSIL --connect-timeout 5 --max-time 10 "${url}" >/dev/null 2>&1; then
    curl_route=(-6)
    log "目标支持 IPv6，优先使用 IPv6 下载"
  fi

  local content_length
  content_length="$(curl "${curl_route[@]}" -fsSIL "${url}" \
    | awk 'BEGIN { IGNORECASE=1 } /^content-length:/ { gsub("\\r", "", $2); value=$2 } END { print value }')"
  if [[ ! "${content_length}" =~ ^[0-9]+$ ]] || (( content_length == 0 )); then
    warn "无法获取文件长度，回退到普通下载: ${url}"
    download_file "${destination}" "${url}"
    return
  fi

  local chunk_size=$(( (content_length + segments - 1) / segments ))
  local index start end expected part tail resume actual pid
  local -a pids=() parts=() active_parts=() active_tails=()
  log "并行下载 $(basename "${destination}") (${segments} 段)"

  for (( index=0; index<segments; index++ )); do
    start=$(( index * chunk_size ))
    (( start < content_length )) || break
    end=$(( start + chunk_size - 1 ))
    (( end < content_length )) || end=$(( content_length - 1 ))
    expected=$(( end - start + 1 ))
    part="${destination}.segment.${index}"
    tail="${part}.tail"
    parts+=("${part}")

    # 上一次中断时 curl 的增量保存在 .tail，先合并，再从精确字节位置续传。
    if [[ -s "${tail}" ]]; then
      cat "${tail}" >>"${part}"
      rm -f "${tail}"
    fi
    actual=0
    [[ -f "${part}" ]] && actual="$(wc -c <"${part}" | tr -d ' ')"
    if [[ "${actual}" == "${expected}" ]]; then
      continue
    fi
    (( actual < expected )) \
      || fail "分段文件大小异常: $(basename "${part}") (${actual}/${expected})"

    resume=$(( start + actual ))
    curl "${curl_route[@]}" -fsSL --retry 6 --retry-delay 2 \
      --range "${resume}-${end}" -o "${tail}" "${url}" &
    pids+=("$!")
    active_parts+=("${part}")
    active_tails+=("${tail}")
  done

  for pid in "${pids[@]}"; do
    wait "${pid}" || fail "并行下载失败: $(basename "${destination}")"
  done

  for (( index=0; index<${#active_parts[@]}; index++ )); do
    cat "${active_tails[index]}" >>"${active_parts[index]}"
    rm -f "${active_tails[index]}"
  done

  local combined="${destination}.parallel.part"
  : >"${combined}"
  for part in "${parts[@]}"; do
    actual="$(wc -c <"${part}" | tr -d ' ')"
    (( actual <= chunk_size )) || fail "分段文件超出预期: $(basename "${part}")"
    cat "${part}" >>"${combined}"
  done
  actual="$(wc -c <"${combined}" | tr -d ' ')"
  [[ "${actual}" == "${content_length}" ]] \
    || fail "下载大小不匹配: $(basename "${destination}") (${actual}/${content_length})"

  mv "${combined}" "${destination}"
  for part in "${parts[@]}"; do
    rm -f "${part}"
  done
}

verify_sha256() {
  local file="$1" expected="${2:-}" actual
  [[ -n "${expected}" ]] || return 0
  actual="$(shasum -a 256 "${file}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] \
    || fail "SHA-256 校验失败: $(basename "${file}")"
  log "SHA-256 校验通过: $(basename "${file}")"
}

extract_once() {
  local archive="$1" expected_dir="$2" destination="$3" temp_dir
  [[ -d "${destination}" ]] && return 0
  temp_dir="$(mktemp -d "${RUNTIME_DIR}/extract.XXXXXX")"
  if ! tar -xzf "${archive}" -C "${temp_dir}"; then
    rm -rf "${temp_dir}"
    fail "归档损坏: ${archive}"
  fi
  [[ -d "${temp_dir}/${expected_dir}" ]] || fail "归档中未找到 ${expected_dir}"
  mv "${temp_dir}/${expected_dir}" "${destination}"
  rmdir "${temp_dir}"
}

ensure_postgres() {
  if command -v pg_ctl >/dev/null 2>&1 && command -v psql >/dev/null 2>&1; then
    return 0
  fi
  if [[ "${OS_NAME}" != "Darwin" ]]; then
    fail "Linux 环境请先安装 PostgreSQL 17 客户端与服务端工具"
  fi
  require_command brew
  local brew_prefix postgres_bin
  brew_prefix="$(brew --prefix)"
  postgres_bin="${brew_prefix}/opt/${POSTGRES_FORMULA}/bin"
  if [[ ! -x "${postgres_bin}/pg_ctl" ]]; then
    log "通过 Homebrew 安装 ${POSTGRES_FORMULA}"
    HOMEBREW_NO_AUTO_UPDATE=1 brew install "${POSTGRES_FORMULA}"
  fi
  export PATH="${postgres_bin}:${PATH}"
  require_command pg_ctl
  require_command psql
}

ensure_redis() {
  local archive="${DOWNLOAD_DIR}/redis-${REDIS_VERSION}.tar.gz"
  if [[ ! -d "${REDIS_HOME}" ]]; then
    download_file "${archive}" "https://download.redis.io/releases/redis-${REDIS_VERSION}.tar.gz"
    extract_once "${archive}" "redis-${REDIS_VERSION}" "${REDIS_HOME}"
  fi
  if [[ ! -x "${REDIS_HOME}/src/redis-server" ]]; then
    require_command make
    [[ -x /usr/bin/clang ]] || fail "编译 Redis 需要 Apple Clang (/usr/bin/clang)"
    log "编译 Redis ${REDIS_VERSION}"
    # 某些开发工具会在 PATH 前面放置名为 cc 的命令（例如 Claude Code CLI），
    # 因此不能依赖 Makefile 默认的 `cc`，必须显式使用 Apple Clang。
    # 上一次失败的构建可能留下不完整对象，先清理再重新编译。
    make -C "${REDIS_HOME}" distclean >/dev/null 2>&1 || true
    make -C "${REDIS_HOME}" -j"$(cpu_count)" \
      CC=/usr/bin/clang AR=/usr/bin/ar \
      MALLOC=libc BUILD_TLS=no
  fi
}

ensure_kafka() {
  local name="kafka_${KAFKA_SCALA_VERSION}-${KAFKA_VERSION}"
  local archive="${DOWNLOAD_DIR}/${name}.tgz"
  if [[ ! -x "${KAFKA_HOME}/bin/kafka-server-start.sh" ]]; then
    download_file "${archive}" \
      "https://mirrors.aliyun.com/apache/kafka/${KAFKA_VERSION}/${name}.tgz" \
      "https://mirrors.cloud.tencent.com/apache/kafka/${KAFKA_VERSION}/${name}.tgz" \
      "https://mirrors.huaweicloud.com/apache/kafka/${KAFKA_VERSION}/${name}.tgz" \
      "https://downloads.apache.org/kafka/${KAFKA_VERSION}/${name}.tgz" \
      "https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/${name}.tgz"
    extract_once "${archive}" "${name}" "${KAFKA_HOME}"
  fi
}

ensure_elasticsearch() {
  local name="elasticsearch-${ELASTICSEARCH_VERSION}-${ES_PLATFORM}"
  local archive="${DOWNLOAD_DIR}/${name}.tar.gz"
  local download_url="${ELASTICSEARCH_DOWNLOAD_URL:-https://artifacts.elastic.co/downloads/elasticsearch/${name}.tar.gz}"
  if [[ ! -x "${ELASTICSEARCH_HOME}/bin/elasticsearch" ]]; then
    download_file_parallel "${archive}" "${download_url}" 8
    extract_once "${archive}" "elasticsearch-${ELASTICSEARCH_VERSION}" "${ELASTICSEARCH_HOME}"
  fi
}

ensure_minio() {
  local minio_url="${MINIO_DOWNLOAD_URL:-}"
  local mc_url="${MINIO_MC_DOWNLOAD_URL:-}"
  local minio_sha256="" mc_sha256=""
  if [[ -z "${minio_url}" && "${OS_NAME}:${CPU_ARCH}" == "Darwin:arm64" ]]; then
    minio_url="https://gh-proxy.com/https://github.com/minio/minio/releases/download/${MINIO_VERSION}/minio.darwin-arm64.${MINIO_VERSION}"
    minio_sha256="${MINIO_DARWIN_ARM64_SHA256:-}"
  fi
  if [[ -z "${mc_url}" && "${OS_NAME}:${CPU_ARCH}" == "Darwin:arm64" ]]; then
    mc_url="https://gh-proxy.com/https://github.com/minio/mc/releases/download/${MINIO_MC_VERSION}/mc.darwin-arm64.${MINIO_MC_VERSION}"
    mc_sha256="${MINIO_MC_DARWIN_ARM64_SHA256:-}"
  fi
  minio_url="${minio_url:-https://dl.min.io/server/minio/release/${MINIO_PLATFORM}/archive/minio.${MINIO_VERSION}}"
  mc_url="${mc_url:-https://dl.min.io/client/mc/release/${MINIO_PLATFORM}/archive/mc.${MINIO_MC_VERSION}}"

  mkdir -p "${MINIO_HOME}"
  if [[ ! -x "${MINIO_HOME}/minio" ]]; then
    download_file_parallel "${MINIO_HOME}/minio" "${minio_url}" 8
    verify_sha256 "${MINIO_HOME}/minio" "${minio_sha256}"
    chmod +x "${MINIO_HOME}/minio"
  fi
  if [[ ! -x "${MINIO_HOME}/mc" ]]; then
    download_file_parallel "${MINIO_HOME}/mc" "${mc_url}" 8
    verify_sha256 "${MINIO_HOME}/mc" "${mc_sha256}"
    chmod +x "${MINIO_HOME}/mc"
  fi
}

install_all() {
  prepare_directories
  load_local_env
  configure_java
  require_command curl
  require_command nc
  ensure_postgres
  ensure_redis
  ensure_kafka
  ensure_elasticsearch
  ensure_minio
  log "中间件安装完成；二进制位于 ${RUNTIME_DIR}，PostgreSQL 由 ${POSTGRES_FORMULA} 提供"
}

start_postgres() {
  ensure_postgres
  if port_open "${POSTGRES_PORT}"; then
    log "PostgreSQL 已在运行"
  else
    if [[ ! -f "${POSTGRES_DATA}/PG_VERSION" ]]; then
      log "初始化 PostgreSQL 数据目录"
      local password_file
      password_file="$(mktemp "${LOCAL_DIR}/postgres/run/password.XXXXXX")"
      printf '%s\n' "${POSTGRES_PASSWORD}" >"${password_file}"
      initdb -D "${POSTGRES_DATA}" -U "${POSTGRES_USER}" --pwfile="${password_file}" \
        --auth-local=trust --auth-host=scram-sha-256 >/dev/null
      rm -f "${password_file}"
    fi
    pg_ctl -D "${POSTGRES_DATA}" -l "${LOCAL_DIR}/postgres/logs/postgres.log" \
      -o "-h 127.0.0.1 -p ${POSTGRES_PORT}" start >/dev/null
  fi
  wait_port PostgreSQL "${POSTGRES_PORT}" 60
  export PGPASSWORD="${POSTGRES_PASSWORD}"
  if ! psql -h 127.0.0.1 -p "${POSTGRES_PORT}" -U "${POSTGRES_USER}" -d postgres \
    -Atc "SELECT 1 FROM pg_database WHERE datname='${POSTGRES_DB}'" | grep -qx 1; then
    createdb -h 127.0.0.1 -p "${POSTGRES_PORT}" -U "${POSTGRES_USER}" -O "${POSTGRES_USER}" "${POSTGRES_DB}"
  fi
  psql -h 127.0.0.1 -p "${POSTGRES_PORT}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS content AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS feature_registry AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS experiment AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS model_registry AUTHORIZATION ${POSTGRES_USER}; CREATE SCHEMA IF NOT EXISTS outbox AUTHORIZATION ${POSTGRES_USER};" >/dev/null
}

start_redis() {
  if "${REDIS_HOME}/src/redis-cli" -h 127.0.0.1 -p "${REDIS_PORT}" ping 2>/dev/null | grep -qx PONG; then
    log "Redis 已在运行"
    return
  fi
  local config="${CONFIG_DIR}/redis.generated.conf"
  printf '%s\n' \
    'bind 127.0.0.1' \
    "port ${REDIS_PORT}" \
    'protected-mode yes' \
    'daemonize yes' \
    "dir ${LOCAL_DIR}/redis/data" \
    "pidfile ${LOCAL_DIR}/redis/run/redis.pid" \
    "logfile ${LOCAL_DIR}/redis/logs/redis.log" \
    'appendonly yes' \
    'appenddirname appendonlydir' \
    'maxmemory-policy allkeys-lru' >"${config}"
  "${REDIS_HOME}/src/redis-server" "${config}"
  wait_port Redis "${REDIS_PORT}" 30
}

write_kafka_config() {
  local config="${CONFIG_DIR}/kafka.generated.properties"
  local controller_port=$((KAFKA_PORT + 1))
  printf '%s\n' \
    'process.roles=broker,controller' \
    'node.id=1' \
    "controller.quorum.voters=1@127.0.0.1:${controller_port}" \
    'controller.listener.names=CONTROLLER' \
    "listeners=PLAINTEXT://127.0.0.1:${KAFKA_PORT},CONTROLLER://127.0.0.1:${controller_port}" \
    "advertised.listeners=PLAINTEXT://127.0.0.1:${KAFKA_PORT}" \
    'listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT' \
    'inter.broker.listener.name=PLAINTEXT' \
    "log.dirs=${LOCAL_DIR}/kafka/data" \
    'num.partitions=3' \
    'default.replication.factor=1' \
    'offsets.topic.replication.factor=1' \
    'transaction.state.log.replication.factor=1' \
    'transaction.state.log.min.isr=1' \
    'group.initial.rebalance.delay.ms=0' \
    'auto.create.topics.enable=false' >"${config}"
}

create_kafka_topics() {
  local topics=(
    interaction.raw.v1 interaction.validated.v1 exposure.logged.v1
    content.submitted.v1 content.profile.ready.v1 content.profile.published.v1
    content.distribution.changed.v1 feature.snapshot.updated.v1 model.version.activated.v1
  )
  local topic
  for topic in "${topics[@]}"; do
    "${KAFKA_HOME}/bin/kafka-topics.sh" --bootstrap-server "127.0.0.1:${KAFKA_PORT}" \
      --create --if-not-exists --topic "${topic}" --partitions 3 --replication-factor 1 >/dev/null
  done
  log "Kafka 业务 Topics 已初始化"
}

start_kafka() {
  if port_open "${KAFKA_PORT}"; then
    log "Kafka 已在运行"
    create_kafka_topics
    return
  fi
  write_kafka_config
  local config="${CONFIG_DIR}/kafka.generated.properties"
  if [[ ! -f "${LOCAL_DIR}/kafka/data/meta.properties" ]]; then
    log "格式化 Kafka KRaft 存储"
    local cluster_id
    cluster_id="$("${KAFKA_HOME}/bin/kafka-storage.sh" random-uuid)"
    # controller.quorum.voters 使用静态 quorum；Kafka 4.3 不允许再与
    # dynamic-quorum 的 --standalone 参数组合。
    "${KAFKA_HOME}/bin/kafka-storage.sh" format -t "${cluster_id}" -c "${config}" >/dev/null
  fi
  KAFKA_HEAP_OPTS="-Xms256m -Xmx256m" \
    nohup "${KAFKA_HOME}/bin/kafka-server-start.sh" "${config}" \
    >"${LOCAL_DIR}/kafka/logs/server.log" 2>&1 </dev/null &
  printf '%s\n' "$!" >"${LOCAL_DIR}/kafka/run/kafka.pid"
  wait_port Kafka "${KAFKA_PORT}" 180
  create_kafka_topics
}

start_elasticsearch() {
  if curl --noproxy '*' -fsS "http://127.0.0.1:${ELASTICSEARCH_PORT}/" >/dev/null 2>&1; then
    log "Elasticsearch 已在运行"
    curl --noproxy '*' -fsS -X PUT \
      "http://127.0.0.1:${ELASTICSEARCH_PORT}/_cluster/settings" \
      -H 'Content-Type: application/json' \
      -d '{"persistent":{"cluster.routing.allocation.disk.threshold_enabled":false}}' >/dev/null
    return
  fi
  local yml="${ELASTICSEARCH_HOME}/config/elasticsearch.yml"
  printf '%s\n' \
    'cluster.name: seekflux-local' \
    'node.name: seekflux-node-1' \
    "path.data: ${LOCAL_DIR}/elasticsearch/data" \
    "path.logs: ${LOCAL_DIR}/elasticsearch/logs" \
    'network.host: 127.0.0.1' \
    "http.port: ${ELASTICSEARCH_PORT}" \
    'discovery.type: single-node' \
    'cluster.routing.allocation.disk.threshold_enabled: false' \
    'xpack.security.enabled: false' \
    'xpack.security.enrollment.enabled: false' \
    'xpack.ml.enabled: false' \
    'xpack.watcher.enabled: false' \
    'ingest.geoip.downloader.enabled: false' >"${yml}"
  mkdir -p "${ELASTICSEARCH_HOME}/config/jvm.options.d"
  printf '%s\n' '-Xms512m' '-Xmx512m' >"${ELASTICSEARCH_HOME}/config/jvm.options.d/seekflux.options"
  nohup "${ELASTICSEARCH_HOME}/bin/elasticsearch" -p "${LOCAL_DIR}/elasticsearch/run/elasticsearch.pid" \
    >"${LOCAL_DIR}/elasticsearch/logs/console.log" 2>&1 </dev/null &
  wait_http Elasticsearch "http://127.0.0.1:${ELASTICSEARCH_PORT}/" 300
  curl --noproxy '*' -fsS -X PUT \
    "http://127.0.0.1:${ELASTICSEARCH_PORT}/_cluster/settings" \
    -H 'Content-Type: application/json' \
    -d '{"persistent":{"cluster.routing.allocation.disk.threshold_enabled":false}}' >/dev/null
}

start_minio() {
  if curl --noproxy '*' -fsS "http://127.0.0.1:${MINIO_API_PORT}/minio/health/live" >/dev/null 2>&1; then
    log "MinIO 已在运行"
    return
  fi
  MINIO_ROOT_USER="${MINIO_ROOT_USER}" MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
    nohup "${MINIO_HOME}/minio" server "${LOCAL_DIR}/minio/data" \
      --address "127.0.0.1:${MINIO_API_PORT}" --console-address "127.0.0.1:${MINIO_CONSOLE_PORT}" \
      >"${LOCAL_DIR}/minio/logs/minio.log" 2>&1 </dev/null &
  printf '%s\n' "$!" >"${LOCAL_DIR}/minio/run/minio.pid"
  wait_http MinIO "http://127.0.0.1:${MINIO_API_PORT}/minio/health/live" 90
  MC_CONFIG_DIR="${LOCAL_DIR}/minio/mc-config" "${MINIO_HOME}/mc" alias set seekflux-local \
    "http://127.0.0.1:${MINIO_API_PORT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null
  MC_CONFIG_DIR="${LOCAL_DIR}/minio/mc-config" "${MINIO_HOME}/mc" mb --ignore-existing \
    seekflux-local/seekflux-artifacts seekflux-local/seekflux-checkpoints >/dev/null
  log "MinIO Buckets 已初始化；控制台 http://127.0.0.1:${MINIO_CONSOLE_PORT}"
}

infra_up() {
  prepare_directories
  load_local_env
  configure_java
  require_command curl
  require_command nc

  ensure_postgres
  start_postgres

  ensure_redis
  start_redis

  ensure_kafka
  start_kafka

  ensure_elasticsearch
  start_elasticsearch

  ensure_minio
  start_minio
}

find_app_jar() {
  local app="$1"
  find "${ROOT_DIR}/apps/${app}/target" -maxdepth 1 -type f -name "${app}-*.jar" \
    ! -name '*.original' | sort | tail -n 1
}

launchd_label() {
  printf 'io.seekflux.local.%s\n' "$1"
}

launchd_pid() {
  local label="$1"
  launchctl print "gui/$(id -u)/${label}" 2>/dev/null \
    | awk '$1 == "pid" && $2 == "=" { print $3; exit }'
}

build_apps() {
  configure_java
  require_command mvn
  log "构建 Content、Worker、Online 与 Agent 四个应用"
  (cd "${ROOT_DIR}" && mvn -DskipTests package)
}

start_java_app() {
  local name="$1" app="$2" health_url="${3:-}" pidfile log_file jar
  pidfile="${LOCAL_DIR}/apps/run/${app}.pid"
  log_file="${LOCAL_DIR}/apps/logs/${app}.log"
  if [[ -n "${health_url}" ]] && curl --noproxy '*' -fsS "${health_url}" >/dev/null 2>&1; then
    log "${name} 已在运行"
    return
  fi
  if [[ "${OS_NAME}" != "Darwin" && -f "${pidfile}" ]]; then
    local old_pid
    old_pid="$(cat "${pidfile}")"
    if [[ "${old_pid}" =~ ^[0-9]+$ ]] && kill -0 "${old_pid}" 2>/dev/null; then
      log "${name} 已在运行 (PID ${old_pid})"
      return
    fi
  fi
  jar="$(find_app_jar "${app}")"
  [[ -n "${jar}" ]] || fail "未找到 ${app} 可执行 Jar，请先执行 ./seekflux.sh build"

  local app_pid
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    local label
    label="$(launchd_label "${app}")"
    app_pid="$(launchd_pid "${label}" || true)"
    if [[ "${app_pid}" =~ ^[0-9]+$ ]] && kill -0 "${app_pid}" 2>/dev/null; then
      log "${name} 已在运行 (PID ${app_pid})"
      return
    fi
    launchctl remove "${label}" >/dev/null 2>&1 || true
    launchctl submit -l "${label}" -o "${log_file}" -e "${log_file}" -- \
      "${ROOT_DIR}/deploy/local/run-java-app.sh" "${jar}"
    for _ in {1..20}; do
      app_pid="$(launchd_pid "${label}" || true)"
      [[ "${app_pid}" =~ ^[0-9]+$ ]] && break
      sleep 0.25
    done
    [[ "${app_pid}" =~ ^[0-9]+$ ]] || fail "${name} 未能注册到 launchd"
  else
    nohup "${JAVA_HOME}/bin/java" -jar "${jar}" >"${log_file}" 2>&1 </dev/null &
    app_pid="$!"
  fi
  printf '%s\n' "${app_pid}" >"${pidfile}"
  if [[ -n "${health_url}" ]]; then
    wait_http "${name}" "${health_url}" 120
  else
    sleep 3
    kill -0 "${app_pid}" 2>/dev/null || fail "${name} 启动失败，请查看 ${log_file}"
    log "${name} 已启动 (PID ${app_pid})"
  fi
}

apps_up() {
  load_local_env
  build_apps
  start_java_app "Content Server" content-server "http://127.0.0.1:${CONTENT_SERVER_PORT}/actuator/health"
  start_java_app "Worker Runner" worker-runner
  start_java_app "Online Server" online-server "http://127.0.0.1:${ONLINE_SERVER_PORT}/actuator/health"
  start_java_app "Agent Server" agent-server "http://127.0.0.1:${AGENT_SERVER_PORT}/actuator/health"
  start_web_app
}

start_web_app() {
  local pidfile="${LOCAL_DIR}/apps/run/web.pid"
  local log_file="${LOCAL_DIR}/apps/logs/web.log"
  local node_bin_dir npm_bin_dir
  if curl --noproxy '*' -fsS "http://localhost:${WEB_SERVER_PORT}/" >/dev/null 2>&1; then
    log "Web 已在运行"
    return
  fi
  require_command node
  require_command npm
  node_bin_dir="$(dirname "$(command -v node)")"
  npm_bin_dir="$(dirname "$(command -v npm)")"
  if [[ ! -x "${ROOT_DIR}/apps/web/node_modules/.bin/vinext" ]]; then
    log "安装 Web 依赖"
    (cd "${ROOT_DIR}/apps/web" && npm ci)
  fi

  local app_pid
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    local label
    label="$(launchd_label web)"
    app_pid="$(launchd_pid "${label}" || true)"
    if [[ "${app_pid}" =~ ^[0-9]+$ ]] && kill -0 "${app_pid}" 2>/dev/null; then
      log "Web 已在运行 (PID ${app_pid})"
      return
    fi
    launchctl remove "${label}" >/dev/null 2>&1 || true
    launchctl submit -l "${label}" -o "${log_file}" -e "${log_file}" -- \
      "${ROOT_DIR}/deploy/local/run-web-app.sh" "${WEB_SERVER_PORT}" "${node_bin_dir}" "${npm_bin_dir}"
    for _ in {1..20}; do
      app_pid="$(launchd_pid "${label}" || true)"
      [[ "${app_pid}" =~ ^[0-9]+$ ]] && break
      sleep 0.25
    done
    [[ "${app_pid}" =~ ^[0-9]+$ ]] || fail "Web 未能注册到 launchd"
  else
    nohup "${ROOT_DIR}/deploy/local/run-web-app.sh" "${WEB_SERVER_PORT}" "${node_bin_dir}" "${npm_bin_dir}" >"${log_file}" 2>&1 </dev/null &
    app_pid="$!"
  fi
  printf '%s\n' "${app_pid}" >"${pidfile}"
  wait_http "Web" "http://localhost:${WEB_SERVER_PORT}/" 120
}

stop_pidfile() {
  local name="$1" pidfile="$2" pid elapsed=0
  [[ -f "${pidfile}" ]] || return 0
  pid="$(cat "${pidfile}")"
  if [[ "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null || true
    while kill -0 "${pid}" 2>/dev/null && (( elapsed < 20 )); do
      sleep 1
      elapsed=$((elapsed + 1))
    done
    if kill -0 "${pid}" 2>/dev/null; then
      kill -KILL "${pid}" 2>/dev/null || true
    fi
    log "${name} 已停止"
  fi
  rm -f "${pidfile}"
}

apps_down() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    local app label
    for app in web agent-server online-server worker-runner content-server; do
      label="$(launchd_label "${app}")"
      if launchctl print "gui/$(id -u)/${label}" >/dev/null 2>&1; then
        launchctl remove "${label}" >/dev/null 2>&1 || true
        log "${app} 已停止"
      fi
      # 兼容升级脚本前由 nohup 启动、尚未注册到 launchd 的旧进程。
      stop_pidfile "${app}" "${LOCAL_DIR}/apps/run/${app}.pid"
    done
    return
  fi
  stop_pidfile "Online Server" "${LOCAL_DIR}/apps/run/online-server.pid"
  stop_pidfile "Agent Server" "${LOCAL_DIR}/apps/run/agent-server.pid"
  stop_pidfile "Worker Runner" "${LOCAL_DIR}/apps/run/worker-runner.pid"
  stop_pidfile "Content Server" "${LOCAL_DIR}/apps/run/content-server.pid"
  stop_pidfile "Web" "${LOCAL_DIR}/apps/run/web.pid"
}

infra_down() {
  load_local_env
  stop_pidfile MinIO "${LOCAL_DIR}/minio/run/minio.pid"
  stop_pidfile Elasticsearch "${LOCAL_DIR}/elasticsearch/run/elasticsearch.pid"
  stop_pidfile Kafka "${LOCAL_DIR}/kafka/run/kafka.pid"
  if [[ -x "${REDIS_HOME}/src/redis-cli" ]]; then
    "${REDIS_HOME}/src/redis-cli" -h 127.0.0.1 -p "${REDIS_PORT}" shutdown >/dev/null 2>&1 || true
  fi
  ensure_postgres
  if [[ -f "${POSTGRES_DATA}/postmaster.pid" ]]; then
    pg_ctl -D "${POSTGRES_DATA}" stop -m fast >/dev/null || true
    log "PostgreSQL 已停止"
  fi
}

service_status() {
  local name="$1" port="$2"
  if port_open "${port}"; then
    log "${name}: UP :${port}"
  else
    log "${name}: DOWN :${port}"
    return 1
  fi
}

web_status() {
  if curl --noproxy '*' -fsS "http://localhost:${WEB_SERVER_PORT}/" >/dev/null 2>&1; then
    log "Web: UP :${WEB_SERVER_PORT}"
  else
    log "Web: DOWN :${WEB_SERVER_PORT}"
    return 1
  fi
}

status_all() {
  prepare_directories
  load_local_env
  local failed=0
  service_status PostgreSQL "${POSTGRES_PORT}" || failed=1
  service_status Redis "${REDIS_PORT}" || failed=1
  service_status Kafka "${KAFKA_PORT}" || failed=1
  service_status Elasticsearch "${ELASTICSEARCH_PORT}" || failed=1
  service_status MinIO "${MINIO_API_PORT}" || failed=1
  service_status "Content Server" "${CONTENT_SERVER_PORT}" || failed=1
  service_status "Online Server" "${ONLINE_SERVER_PORT}" || failed=1
  service_status "Agent Server" "${AGENT_SERVER_PORT}" || failed=1
  web_status || failed=1
  local worker_pidfile="${LOCAL_DIR}/apps/run/worker-runner.pid"
  local worker_pid=""
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    worker_pid="$(launchd_pid "$(launchd_label worker-runner)" || true)"
  elif [[ -f "${worker_pidfile}" ]]; then
    worker_pid="$(cat "${worker_pidfile}")"
  fi
  if [[ "${worker_pid}" =~ ^[0-9]+$ ]] && kill -0 "${worker_pid}" 2>/dev/null; then
    log "Worker Runner: UP (PID ${worker_pid})"
  else
    log "Worker Runner: DOWN"
    failed=1
  fi
  return "${failed}"
}

doctor() {
  log "平台: ${OS_NAME} ${CPU_ARCH}"
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    command -v brew >/dev/null 2>&1 && log "Homebrew: $(brew --version | head -n 1)" || warn "未安装 Homebrew"
    xcode-select -p >/dev/null 2>&1 && log "Command Line Tools: OK" || warn "缺少 Xcode Command Line Tools"
  fi
  command -v curl >/dev/null 2>&1 && log "curl: OK" || warn "curl: MISSING"
  command -v make >/dev/null 2>&1 && log "make: OK" || warn "make: MISSING"
  if command -v java >/dev/null 2>&1; then
    configure_java
    log "Java: $(java -version 2>&1 | head -n 1)"
  else
    warn "Java: MISSING (需要 JDK 21+)"
  fi
  command -v mvn >/dev/null 2>&1 && log "Maven: $(mvn -version | head -n 1)" || warn "Maven: MISSING"
  log "运行时版本: PostgreSQL ${POSTGRES_FORMULA}, Redis ${REDIS_VERSION}, Kafka ${KAFKA_VERSION}, Elasticsearch ${ELASTICSEARCH_VERSION}, MinIO ${MINIO_VERSION}"
}

show_logs() {
  local target="${1:-all}"
  local files=()
  case "${target}" in
    content) files=("${LOCAL_DIR}/apps/logs/content-server.log") ;;
    worker) files=("${LOCAL_DIR}/apps/logs/worker-runner.log") ;;
    online) files=("${LOCAL_DIR}/apps/logs/online-server.log") ;;
    agent) files=("${LOCAL_DIR}/apps/logs/agent-server.log") ;;
    web) files=("${LOCAL_DIR}/apps/logs/web.log") ;;
    postgres) files=("${LOCAL_DIR}/postgres/logs/postgres.log") ;;
    redis) files=("${LOCAL_DIR}/redis/logs/redis.log") ;;
    kafka) files=("${LOCAL_DIR}/kafka/logs/server.log") ;;
    elasticsearch) files=("${LOCAL_DIR}/elasticsearch/logs/console.log") ;;
    minio) files=("${LOCAL_DIR}/minio/logs/minio.log") ;;
    all) files=("${LOCAL_DIR}/apps/logs/"*.log) ;;
    *) fail "未知日志目标: ${target}" ;;
  esac
  local file
  for file in "${files[@]}"; do
    [[ -f "${file}" ]] || continue
    printf '\n===== %s =====\n' "${file#${ROOT_DIR}/}"
    tail -n 100 "${file}"
  done
}

open_pages() {
  [[ "${OS_NAME}" == "Darwin" ]] || fail "open 命令目前只支持 macOS"
  load_local_env
  open "http://localhost:${WEB_SERVER_PORT}/"
}

usage() {
  cat <<'EOF'
SeekFlux macOS 本地开发环境

用法: ./seekflux.sh <command>

  doctor            检查 macOS、Homebrew、JDK、Maven 与编译工具
  versions          显示固定的中间件版本
  install           安装/升级中间件，不启动服务
  infra-up          安装并启动 PostgreSQL、Redis、Kafka、ES、MinIO
  build             构建四个 Java 应用
  apps-up           构建并启动 Content、Worker、Online、Agent 与 Web
  seed-demo         通过内容发布链路创建六类画像示例视频
  up                 安装并启动中间件、四个 Java 应用与 Web
  status             查看全部中间件与应用状态
  logs [name]        查看日志；name: content|worker|online|agent|web|postgres|redis|kafka|elasticsearch|minio
  open               在 macOS 浏览器打开唯一 Web 前端
  apps-down          停止四个 Java 应用与 Web
  infra-down         停止项目管理的中间件进程
  down               停止应用和中间件
  restart            重启完整本地环境

升级版本：修改 deploy/local/versions.env 后重新执行 install 或 up。
EOF
}

prepare_directories
load_local_env

case "${1:-up}" in
  doctor) doctor ;;
  versions) cat "${VERSION_FILE}" ;;
  install|download) install_all ;;
  infra-up) infra_up ;;
  build) build_apps ;;
  apps-up) apps_up ;;
  seed-demo) bash "${ROOT_DIR}/deploy/local/seed-demo-content.sh" "${CONTENT_SERVER_PORT}" "${ONLINE_SERVER_PORT}" ;;
  up|start) infra_up; apps_up; status_all ;;
  status) status_all ;;
  logs) show_logs "${2:-all}" ;;
  open) open_pages ;;
  apps-down) apps_down ;;
  infra-down) infra_down ;;
  down|stop) apps_down; infra_down ;;
  restart) apps_down; infra_down; infra_up; apps_up; status_all ;;
  help|-h|--help) usage ;;
  *) usage; exit 2 ;;
esac
