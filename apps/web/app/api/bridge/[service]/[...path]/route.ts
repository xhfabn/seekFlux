type ServiceName = "content" | "online";

type RouteContext = {
  params: Promise<{ service: string; path: string[] }>;
};

const SERVICE_BASES: Record<ServiceName, string> = {
  content: process.env.SEEKFLUX_CONTENT_API_BASE ?? "http://127.0.0.1:8081",
  online: process.env.SEEKFLUX_ONLINE_API_BASE ?? "http://127.0.0.1:8080",
};

async function proxy(request: Request, context: RouteContext): Promise<Response> {
  const { service, path } = await context.params;
  if (service !== "content" && service !== "online") {
    return Response.json({ message: "未知的 SeekFlux 服务" }, { status: 404 });
  }

  const sourceUrl = new URL(request.url);
  const base = SERVICE_BASES[service];
  const target = new URL(path.map(encodeURIComponent).join("/"), `${base.replace(/\/$/, "")}/`);
  target.search = sourceUrl.search;

  const headers = new Headers();
  for (const name of ["content-type", "x-user-id", "idempotency-key"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD"
        ? undefined
        : await request.arrayBuffer(),
      signal: controller.signal,
    });
    const responseHeaders = new Headers();
    const contentType = response.headers.get("content-type");
    if (contentType) responseHeaders.set("content-type", contentType);
    const location = response.headers.get("location");
    if (location) responseHeaders.set("location", location);
    return new Response(response.body, {
      status: response.status,
      headers: responseHeaders,
    });
  } catch (error) {
    const message = error instanceof Error && error.name === "AbortError"
      ? "后端响应超时，请检查服务状态"
      : "暂时无法连接 SeekFlux 后端服务";
    return Response.json(
      { message, service, target: `${target.origin}` },
      { status: 503 },
    );
  } finally {
    clearTimeout(timeout);
  }
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
