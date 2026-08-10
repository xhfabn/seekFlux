import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  return worker.fetch(
    new Request("http://localhost/", { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("renders the SeekFlux consumer app and operator consoles", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);
  const html = await response.text();
  assert.match(html, /SeekFlux/);
  assert.match(html, /从搜索走向发现/);
  assert.match(html, /搜索你感兴趣的内容/);
  assert.match(html, /用户画像/);
  assert.match(html, /内容工作台/);
  assert.match(html, /精选推荐/);
  assert.match(html, /当前画像暂无匹配内容/);
  assert.doesNotMatch(html, /preview_/);
  assert.doesNotMatch(html, /codex-preview|Building your site|react-loading-skeleton/i);
});

test("keeps operator workspaces task-oriented", async () => {
  const source = await readFile(new URL("../app/SeekFluxApp.tsx", import.meta.url), "utf8");
  assert.match(source, /设置身份与兴趣/);
  assert.match(source, /登记新内容/);
  assert.match(source, /校准并发布画像/);
  assert.doesNotMatch(source, /理解用户，|把一条视频，|PRODUCT SHELL|preview_/);
});

test("exposes task-oriented Agent search through the backend bridge", async () => {
  const source = await readFile(new URL("../app/SeekFluxApp.tsx", import.meta.url), "utf8");
  const bridge = await readFile(new URL("../app/api/bridge/[service]/[...path]/route.ts", import.meta.url), "utf8");
  assert.match(source, /AI 搜索/);
  assert.match(source, /多轮筛选内容/);
  assert.match(source, /\/api\/bridge\/agent\/v1\/agent\/search/);
  assert.match(source, /constraintPatch/);
  assert.match(source, /cancelAgentSearch/);
  assert.match(bridge, /SEEKFLUX_AGENT_API_BASE/);
  assert.doesNotMatch(source, /api\.longcat\.ai|AGENT_LLM_API_KEY|ak_[A-Za-z0-9]+/);
});
