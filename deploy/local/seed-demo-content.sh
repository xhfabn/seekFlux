#!/usr/bin/env bash
set -Eeuo pipefail

CONTENT_PORT="${1:-8081}"
ONLINE_PORT="${2:-8080}"
CONTENT_BASE="http://127.0.0.1:${CONTENT_PORT}"
ONLINE_BASE="http://127.0.0.1:${ONLINE_PORT}"

log() { printf '[SeekFlux] %s\n' "$*"; }
fail() { printf '[SeekFlux] ERROR: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || fail "缺少命令: curl"
command -v node >/dev/null 2>&1 || fail "缺少命令: node"
curl --noproxy '*' -fsS "${CONTENT_BASE}/actuator/health" >/dev/null \
  || fail "Content Server 未启动"
curl --noproxy '*' -fsS "${ONLINE_BASE}/actuator/health" >/dev/null \
  || fail "Online Server 未启动"

content_exists() {
  local title="$1" response
  response="$(curl --noproxy '*' -fsS --get "${ONLINE_BASE}/v1/search" \
    --data-urlencode "q=${title}" --data-urlencode 'page=0' --data-urlencode 'size=20')"
  node -e '
    let text = "";
    process.stdin.on("data", chunk => text += chunk);
    process.stdin.on("end", () => {
      const title = process.argv[1];
      const body = JSON.parse(text);
      process.exit((body.hits || []).some(item => item.title === title) ? 0 : 1);
    });
  ' "${title}" <<<"${response}"
}

publish_sample() {
  local tag="$1" title="$2" description="$3" media_uri="$4"
  local payload response content_id status attempt
  if content_exists "${title}"; then
    log "示例已存在: ${tag}"
    return
  fi

  payload="$(node -e '
    const [tag, title, description, mediaUri] = process.argv.slice(1);
    process.stdout.write(JSON.stringify({
      creatorId: `seekflux-demo-${tag}`,
      mediaUri,
      title,
      description,
      sourceTags: [tag]
    }));
  ' "${tag}" "${title}" "${description}" "${media_uri}")"
  response="$(curl --noproxy '*' -fsS -X POST "${CONTENT_BASE}/v1/contents" \
    -H 'Content-Type: application/json' --data-binary "${payload}")"
  content_id="$(node -e '
    let text = "";
    process.stdin.on("data", chunk => text += chunk);
    process.stdin.on("end", () => process.stdout.write(JSON.parse(text).contentId));
  ' <<<"${response}")"

  status=""
  for attempt in {1..45}; do
    response="$(curl --noproxy '*' -fsS "${CONTENT_BASE}/v1/contents/${content_id}")"
    status="$(node -e '
      let text = "";
      process.stdin.on("data", chunk => text += chunk);
      process.stdin.on("end", () => process.stdout.write(JSON.parse(text).status));
    ' <<<"${response}")"
    [[ "${status}" == "PUBLISHED" ]] && break
    sleep 1
  done
  [[ "${status}" == "PUBLISHED" ]] || fail "示例内容未完成发布: ${title} (${status})"
  log "已发布示例: ${tag} → ${content_id}"
}

publish_sample "露营" "露营画像示例｜杭州周末轻量路线" \
  "适合露营兴趣用户的新手路线、装备和日落营地建议。" \
  "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
publish_sample "亲子" "亲子画像示例｜雨天博物馆一日计划" \
  "适合亲子兴趣用户的室内场馆、时间安排和休息节点。" \
  "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
publish_sample "咖啡" "咖啡画像示例｜手冲风味稳定练习" \
  "适合咖啡兴趣用户的水温、研磨度和注水节奏练习。" \
  "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
publish_sample "摄影" "摄影画像示例｜手机日落曝光设置" \
  "适合摄影兴趣用户的手机曝光、构图和拍摄时段建议。" \
  "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4"
publish_sample "旅行" "旅行画像示例｜川西三天两夜路线" \
  "适合旅行兴趣用户的自驾路线、停留时间和观景机位。" \
  "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4"
publish_sample "科技" "科技画像示例｜AI 办公效率实测" \
  "适合科技兴趣用户的会议纪要、资料整理和效率工具实测。" \
  "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"

log "六类画像示例内容已就绪"
