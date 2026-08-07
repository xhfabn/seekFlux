import assert from "node:assert/strict";
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
  assert.match(html, /杭州周末｜3 个新手也能轻松抵达的露营地/);
  assert.match(html, /精选推荐/);
  assert.doesNotMatch(html, /codex-preview|Building your site|react-loading-skeleton/i);
});
