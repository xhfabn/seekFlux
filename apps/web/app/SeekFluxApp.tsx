"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";

type Workspace = "discover" | "audience" | "studio";
type DiscoverMode = "feed" | "search" | "agent";
type HealthState = "checking" | "online" | "offline";
type EventType = "EXPOSURE" | "CLICK" | "PLAY_START" | "LIKE" | "SAVE" | "PLAY_COMPLETE" | "NOT_INTERESTED";
type InteractionSurface = "FEED" | "SEARCH" | "AGENT";

type InteractionAttribution = {
  requestId: string;
  traceId: string;
  surface: InteractionSurface;
};

type ContentProfile = {
  version: number;
  summary: string;
  tags: string[];
  transcript: string;
};

type ContentResponse = {
  contentId: string;
  creatorId: string;
  contentType: "VIDEO" | "ARTICLE";
  mediaUri: string;
  assetUris: string[];
  title: string;
  description: string;
  body: string;
  sourceTags: string[];
  source?: { provider: string; externalId: string; sourcePageUri: string; author: string; licenseName: string };
  status: "SUBMITTED" | "PROFILE_READY" | "PUBLISHED" | "WITHDRAWN";
  profile?: ContentProfile | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string | null;
};

type ContentItem = {
  contentId: string;
  creatorId: string;
  contentType: "VIDEO" | "ARTICLE";
  mediaUri: string;
  assetUris: string[];
  title: string;
  description: string;
  body: string;
  summary: string;
  tags: string[];
  sourceProvider: string;
  sourcePageUri: string;
  sourceAuthor: string;
  licenseName: string;
  profileVersion: number;
  score: number;
  publishedAt: string;
  sources?: string[];
  reason?: string;
};

type SearchResponse = {
  query: string;
  total: number;
  page: number;
  size: number;
  tookMillis: number;
  hits: ContentItem[];
  trace: {
    requestId: string;
    executionMode: "DIRECT_HYBRID" | "DIRECT_KEYWORD_FALLBACK" | "DIRECT_SEMANTIC_FALLBACK";
    indexVersion: string;
    policyVersion: string;
    tookMillis: number;
    degraded: boolean;
    unavailableSources: string[];
    realtimeFeatureStatus: string;
    realtimeFeatureVersion: string | null;
    realtimeFeatureComputedAt: string | null;
  };
};

type MultimodalSearchResponse = {
  queryModality: "TEXT" | "IMAGE" | "VIDEO";
  modelVersion: string;
  querySegments: number;
  items: Array<{
    contentId: string; contentType: "VIDEO" | "ARTICLE"; mediaUri: string; assetUris: string[];
    title: string; summary: string; tags: string[]; startMillis: number; endMillis: number;
    previewUri: string; score: number; modelVersion: string;
  }>;
};

type MediaUploadResponse = { uri: string; contentType: string; size: number; originalName: string };

type FeedResponse = {
  requestId: string;
  items: ContentItem[];
  nextCursor: string | null;
  degraded: boolean;
  unavailableSources: string[];
  realtimeFeatureStatus: string;
  realtimeFeatureVersion: string | null;
  realtimeFeatureComputedAt: string | null;
};

type AgentSearchResponse = {
  requestId: string;
  agentRunId: string | null;
  sessionId: string;
  turnId: string;
  state: "RESULTS_READY" | "NEED_CLARIFICATION" | "FALLBACK_RESULTS" | "CANCELLED" | "FAILED";
  executionMode: "DIRECT" | "AGENT" | "AGENT_TO_DIRECT_FALLBACK";
  goalVersion: number;
  routeReason: "SIMPLE_QUERY" | "COMPLEX_QUERY" | "MULTI_TURN_PATCH" | "EXPLICIT_DIRECT" | "EXPLICIT_AGENT";
  searchPlan: {
    originalQuery: string;
    rewrittenQuery: string;
    derivedRequiredTags: string[];
    complex: boolean;
    reasons: string[];
  };
  appliedConstraints: { page: number; size: number; requiredTags: string[] };
  clarification: string | null;
  total: number;
  page: number;
  size: number;
  items: ContentItem[];
  agentTrace: {
    tookMillis: number;
    totalTokens: number;
    usageMeasured: boolean;
    decisionProviderVersion: string;
  } | null;
  selectedTool: string | null;
  successfulToolCount: number;
  candidateSetReused: boolean;
  degraded: boolean;
  fallbackReason: string | null;
};

type AgentTurn = {
  id: string;
  query: string;
  status: "loading" | "ready" | "error" | "cancelled";
  response?: AgentSearchResponse;
  error?: string;
};

type UserInterestResponse = {
  userId: string;
  topics: string[];
  updatedAt: string;
};

type ShortTermInterestFeatureResponse = {
  userId: string;
  status: "FRESH" | "MISSING" | "STALE" | "UNAVAILABLE";
  topics: Array<{ topic: string; score: number }>;
  windowStart: string | null;
  windowEnd: string | null;
  computedAt: string | null;
  featureVersion: string | null;
};

type InteractionEvent = {
  eventId: string;
  eventType: EventType;
  requestId: string;
  traceId: string;
  contentId: string;
  position: number;
  surface: InteractionSurface;
  eventTime: string;
};

type InteractionBatchReceipt = {
  replayed: boolean;
  acceptedCount: number;
  duplicateCount: number;
  rejectedCount: number;
};

const sampleContent = {
  creatorId: "seekflux-demo",
  title: "杭州周末露营路线",
  description: "从市区出发的一日露营与日落路线，适合新手和亲子家庭。",
  tags: "露营, 杭州, 周末, 亲子",
  contentType: "VIDEO" as const,
  body: "",
  mediaUri: "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
};

const navItems: Array<{ key: Workspace; number: string; title: string; subtitle: string; role: string }> = [
  { key: "discover", number: "01", title: "发现", subtitle: "搜索 · 推荐 · 相似", role: "C 端应用" },
  { key: "audience", number: "02", title: "用户画像", subtitle: "冷启动 · 行为 · 回流", role: "B 端控制台" },
  { key: "studio", number: "03", title: "内容工作台", subtitle: "上传 · 画像 · 发布", role: "B 端控制台" },
];

const statusIndex: Record<ContentResponse["status"], number> = {
  SUBMITTED: 1,
  PROFILE_READY: 3,
  PUBLISHED: 4,
  WITHDRAWN: 0,
};

function splitTags(value: string): string[] {
  return value.split(/[,，]/).map((tag) => tag.trim()).filter(Boolean);
}

function createEventId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16);
    return (character === "x" ? value : (value & 0x3) | 0x8).toString(16);
  });
}

function isInteractionEvent(value: unknown): value is InteractionEvent {
  if (!value || typeof value !== "object") return false;
  const event = value as Partial<InteractionEvent>;
  return typeof event.eventId === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(event.eventId)
    && typeof event.requestId === "string"
    && typeof event.traceId === "string"
    && (event.surface === "FEED" || event.surface === "SEARCH" || event.surface === "AGENT")
    && typeof event.contentId === "string"
    && typeof event.position === "number"
    && typeof event.eventTime === "string";
}

async function api<T>(url: string, options?: RequestInit): Promise<T> {
  const isFormData = options?.body instanceof FormData;
  const response = await fetch(url, {
    ...options,
    headers: {
      ...(options?.body && !isFormData ? { "Content-Type": "application/json" } : {}),
      ...options?.headers,
    },
  });
  const payload = (await response.json().catch(() => ({}))) as { message?: string; detail?: string; title?: string } & T;
  if (!response.ok) throw new Error(payload.message || payload.detail || payload.title || `${response.status} ${response.statusText}`);
  return payload;
}

async function uploadMedia(file: File): Promise<MediaUploadResponse> {
  const form = new FormData();
  form.append("file", file);
  return api<MediaUploadResponse>("/api/bridge/content/v1/media", { method: "POST", body: form });
}

function shortId(value: string): string {
  return value.length <= 20 ? value : `${value.slice(0, 9)}…${value.slice(-6)}`;
}

function formatEventTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value));
}

function agentReply(response: AgentSearchResponse): string {
  if (response.state === "NEED_CLARIFICATION") return response.clarification || "还需要一个条件才能继续筛选。";
  if (response.state === "CANCELLED") return "这次搜索已停止。";
  if (response.state === "FAILED") return "这次搜索没有完成，请换一种说法再试。";
  if (!response.items.length) return "没有找到同时满足这些条件的内容。你可以放宽一个条件继续找。";
  if (response.state === "FALLBACK_RESULTS") return `智能规划暂时降级，已用稳定搜索返回 ${response.total} 条结果。`;
  return `找到了 ${response.total} 条符合条件的内容。你可以继续补充主题、标签或数量。`;
}

export function SeekFluxApp() {
  const [workspace, setWorkspace] = useState<Workspace>("discover");
  const [discoverMode, setDiscoverMode] = useState<DiscoverMode>("feed");
  const [busy, setBusy] = useState<string | null>(null);
  const [toast, setToast] = useState<{ text: string; error?: boolean } | null>(null);
  const [health, setHealth] = useState<Record<"content" | "online" | "agent", HealthState>>({ content: "checking", online: "checking", agent: "checking" });

  const [creatorId, setCreatorId] = useState(sampleContent.creatorId);
  const [title, setTitle] = useState(sampleContent.title);
  const [description, setDescription] = useState(sampleContent.description);
  const [sourceTags, setSourceTags] = useState(sampleContent.tags);
  const [contentType, setContentType] = useState<"VIDEO" | "ARTICLE">(sampleContent.contentType);
  const [contentBody, setContentBody] = useState(sampleContent.body);
  const [mediaUri, setMediaUri] = useState(sampleContent.mediaUri);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [contentId, setContentId] = useState("");
  const [content, setContent] = useState<ContentResponse | null>(null);
  const [contentMessage, setContentMessage] = useState("等待登记一条内容");
  const [profileVersion, setProfileVersion] = useState(2);
  const [profileSummary, setProfileSummary] = useState("适合亲子家庭和露营新手的杭州周边周末路线，包含日落观景建议。");
  const [profileTags, setProfileTags] = useState("亲子露营, 杭州周边, 新手路线");
  const [transcript, setTranscript] = useState("");

  const [userId, setUserId] = useState("demo-user");
  const [savedUserId, setSavedUserId] = useState("demo-user");
  const [interests, setInterests] = useState("露营, 亲子");
  const [profileSavedAt, setProfileSavedAt] = useState("");
  const [realtimeInterest, setRealtimeInterest] = useState<ShortTermInterestFeatureResponse | null>(null);
  const [profileHydrated, setProfileHydrated] = useState(false);
  const [query, setQuery] = useState("杭州 周末 露营");
  const [searchPage, setSearchPage] = useState(0);
  const [searchData, setSearchData] = useState<SearchResponse | null>(null);
  const [feedSeed, setFeedSeed] = useState("");
  const [feedData, setFeedData] = useState<FeedResponse | null>(null);
  const [feedItems, setFeedItems] = useState<ContentItem[]>([]);
  const [discoverError, setDiscoverError] = useState("");
  const [agentQuery, setAgentQuery] = useState("");
  const [agentSessionId, setAgentSessionId] = useState("");
  const [agentGoalVersion, setAgentGoalVersion] = useState(0);
  const [agentTurns, setAgentTurns] = useState<AgentTurn[]>([]);
  const agentAbortRef = useRef<AbortController | null>(null);

  const [events, setEvents] = useState<InteractionEvent[]>([]);
  const [queueHydrated, setQueueHydrated] = useState(false);
  const [syncMessage, setSyncMessage] = useState("行为暂存在当前浏览器，可在接口可用后批量回传。");

  const showToast = useCallback((text: string, error = false) => setToast({ text, error }), []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 3600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => {
      if (!active) return;
      const rawEvents = window.localStorage.getItem("seekflux.interactions");
      if (rawEvents) {
        try {
          const restored = JSON.parse(rawEvents) as unknown;
          setEvents(Array.isArray(restored) ? restored.filter(isInteractionEvent) : []);
        } catch { window.localStorage.removeItem("seekflux.interactions"); }
      }
      const rawProfile = window.localStorage.getItem("seekflux.viewer-profile");
      if (rawProfile) {
        try {
          const saved = JSON.parse(rawProfile) as { userId?: string; interests?: string; savedAt?: string };
          if (saved.userId) { setUserId(saved.userId); setSavedUserId(saved.userId); }
          if (saved.interests) setInterests(saved.interests);
          if (saved.savedAt) setProfileSavedAt(saved.savedAt);
        } catch { window.localStorage.removeItem("seekflux.viewer-profile"); }
      }
      setQueueHydrated(true);
      setProfileHydrated(true);
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (profileHydrated) void runFeed(null, false, savedUserId, feedSeed, false);
    // Initial feed hydration intentionally runs once after browser profile restoration.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profileHydrated]);

  useEffect(() => {
    if (workspace === "audience") void loadRealtimeInterest(savedUserId);
    // The audience workspace refreshes the server snapshot when opened.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace, savedUserId]);

  useEffect(() => {
    if (queueHydrated) window.localStorage.setItem("seekflux.interactions", JSON.stringify(events));
  }, [events, queueHydrated]);

  useEffect(() => {
    let cancelled = false;
    async function check(service: "content" | "online" | "agent") {
      try {
        await api(`/api/bridge/${service}/actuator/health`);
        if (!cancelled) setHealth((current) => ({ ...current, [service]: "online" }));
      } catch {
        if (!cancelled) setHealth((current) => ({ ...current, [service]: "offline" }));
      }
    }
    void check("content");
    void check("online");
    void check("agent");
    return () => { cancelled = true; };
  }, []);

  const navigate = useCallback((target: Workspace) => {
    setWorkspace(target);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }, []);

  const applyContentResponse = useCallback((data: ContentResponse) => {
    setContent(data);
    setContentId(data.contentId);
    if (data.profile) {
      setProfileVersion(data.profile.version + 1);
      setProfileSummary(data.profile.summary);
      setProfileTags(data.profile.tags.join(", "));
      setTranscript(data.profile.transcript ?? "");
    }
  }, []);

  async function submitContent(event: FormEvent) {
    event.preventDefault();
    setBusy("content-submit");
    setContentMessage("正在登记内容并创建异步处理任务…");
    try {
      let resolvedMediaUri = mediaUri.trim();
      if (selectedFile) {
        const uploaded = await uploadMedia(selectedFile);
        resolvedMediaUri = uploaded.uri;
        setMediaUri(uploaded.uri);
      }
      if (!resolvedMediaUri) throw new Error("请选择媒体文件或填写媒体地址");
      const data = await api<ContentResponse>("/api/bridge/content/v1/contents", {
        method: "POST",
        body: JSON.stringify({
          creatorId, contentType, mediaUri: resolvedMediaUri, assetUris: [resolvedMediaUri], title, description,
          body: contentBody, sourceTags: splitTags(sourceTags),
        }),
      });
      applyContentResponse(data);
      setFeedSeed(data.contentId);
      setContentMessage(`登记成功 · ${data.status} · 聚合版本 v${data.version}`);
      setHealth((current) => ({ ...current, content: "online" }));
      showToast("内容已登记，异步画像任务已创建");
    } catch (error) {
      const message = error instanceof Error ? error.message : "内容登记失败";
      setContentMessage(message);
      setHealth((current) => ({ ...current, content: "offline" }));
      showToast(message, true);
    } finally { setBusy(null); }
  }

  async function loadContent(silent = false): Promise<ContentResponse | null> {
    if (!contentId.trim()) {
      if (!silent) showToast("请先输入内容 ID", true);
      return null;
    }
    if (!silent) setBusy("content-load");
    try {
      const data = await api<ContentResponse>(`/api/bridge/content/v1/contents/${encodeURIComponent(contentId.trim())}`);
      applyContentResponse(data);
      setContentMessage(`${data.status} · 聚合版本 v${data.version}${data.profile ? ` · 画像 v${data.profile.version}` : ""}`);
      setHealth((current) => ({ ...current, content: "online" }));
      return data;
    } catch (error) {
      const message = error instanceof Error ? error.message : "查询失败";
      setContentMessage(message);
      if (!silent) showToast(message, true);
      return null;
    } finally { if (!silent) setBusy(null); }
  }

  async function pollContent() {
    setBusy("content-poll");
    setContentMessage("正在轮询异步处理进度…");
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const data = await loadContent(true);
      if (!data) break;
      if (data.status === "PUBLISHED") {
        setBusy(null);
        showToast("内容画像已发布，可以去发现页验证了");
        return;
      }
      await new Promise((resolve) => window.setTimeout(resolve, 1000));
    }
    setBusy(null);
    setContentMessage((current) => `${current} · 等待超时，请确认 Worker、Kafka 与 Elasticsearch 已启动`);
  }

  async function publishProfile(event: FormEvent) {
    event.preventDefault();
    if (!contentId.trim()) return showToast("请先登记或查询一条内容", true);
    setBusy("profile-publish");
    try {
      await api<ContentResponse>(`/api/bridge/content/v1/contents/${encodeURIComponent(contentId.trim())}/profile`, {
        method: "PUT",
        body: JSON.stringify({ profileVersion, summary: profileSummary, tags: splitTags(profileTags), transcript }),
      });
      const published = await api<ContentResponse>(`/api/bridge/content/v1/contents/${encodeURIComponent(contentId.trim())}/publish`, { method: "POST" });
      applyContentResponse(published);
      setContentMessage(`PUBLISHED · 聚合版本 v${published.version} · 画像 v${published.profile?.version ?? profileVersion}`);
      showToast("画像已校准并重新发布");
    } catch (error) {
      showToast(error instanceof Error ? error.message : "画像发布失败", true);
    } finally { setBusy(null); }
  }

  async function withdrawContent() {
    if (!contentId.trim() || !window.confirm("确认撤回这条内容？它将不再参与搜索与推荐。")) return;
    setBusy("content-withdraw");
    try {
      const data = await api<ContentResponse>(`/api/bridge/content/v1/contents/${encodeURIComponent(contentId.trim())}`, { method: "DELETE" });
      applyContentResponse(data);
      setContentMessage(`WITHDRAWN · 聚合版本 v${data.version}`);
      showToast("内容已撤回");
    } catch (error) {
      showToast(error instanceof Error ? error.message : "撤回失败", true);
    } finally { setBusy(null); }
  }

  const appendExposureEvents = useCallback((items: ContentItem[], attribution: InteractionAttribution, startPosition = 0) => {
    const now = new Date().toISOString();
    setEvents((current) => {
      const next = items.map((item, index) => ({
        eventId: createEventId(), eventType: "EXPOSURE" as const, ...attribution,
        contentId: item.contentId, position: startPosition + index + 1, eventTime: now,
      }));
      return [...next, ...current].slice(0, 200);
    });
  }, []);

  const addInteraction = useCallback((eventType: EventType, item: ContentItem, position: number, attribution: InteractionAttribution) => {
    const nextEvent: InteractionEvent = {
      eventId: createEventId(), eventType, ...attribution, contentId: item.contentId,
      position, eventTime: new Date().toISOString(),
    };
    setEvents((current) => [nextEvent, ...current].slice(0, 200));
    showToast("行为已保存到反馈队列");
  }, [showToast]);

  async function runSearch(targetPage = 0, targetQuery = query) {
    const cleanQuery = targetQuery.trim();
    if (!cleanQuery) return showToast("请输入搜索词", true);
    setBusy("search");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ q: cleanQuery, page: String(targetPage), size: "12" });
      const data = await api<SearchResponse>(`/api/bridge/online/v1/search?${params}`, {
        headers: { "X-User-Id": savedUserId || "anonymous" },
      });
      setQuery(cleanQuery);
      setSearchPage(targetPage);
      setSearchData(data);
      setDiscoverMode("search");
      setHealth((current) => ({ ...current, online: "online" }));
      appendExposureEvents(data.hits, {
        requestId: data.trace.requestId,
        traceId: data.trace.requestId,
        surface: "SEARCH",
      });
      if (!data.hits.length) showToast("没有匹配结果");
    } catch (error) {
      const message = error instanceof Error ? error.message : "搜索失败";
      setDiscoverError(message);
      setHealth((current) => ({ ...current, online: "offline" }));
      showToast(message, true);
    } finally { setBusy(null); }
  }

  async function runMultimodalSearch(file: File) {
    setBusy("search");
    setDiscoverError("");
    try {
      const uploaded = await uploadMedia(file);
      const modality = file.type.startsWith("video/") ? "VIDEO" : "IMAGE";
      const result = await api<MultimodalSearchResponse>("/api/bridge/online/v1/search/multimodal", {
        method: "POST",
        body: JSON.stringify({ modality, input: uploaded.uri, size: 12 }),
      });
      const requestId = `multimodal_${createEventId()}`;
      const hits: ContentItem[] = result.items.map((item) => ({
        ...item, creatorId: "", description: item.summary, body: "", sourceProvider: "",
        sourcePageUri: "", sourceAuthor: "", licenseName: "", profileVersion: 1,
        publishedAt: new Date().toISOString(), sources: ["VISUAL"],
        reason: item.startMillis > 0 ? `命中视频 ${Math.floor(item.startMillis / 1000)} 秒附近` : "视觉相似",
      }));
      setQuery(file.name);
      setSearchPage(0);
      setSearchData({ query: file.name, total: hits.length, page: 0, size: 12, tookMillis: 0, hits,
        trace: { requestId, executionMode: "DIRECT_SEMANTIC_FALLBACK", indexVersion: result.modelVersion,
          policyVersion: "multimodal-segment-v1", tookMillis: 0, degraded: false,
          unavailableSources: [], realtimeFeatureStatus: "NOT_APPLICABLE",
          realtimeFeatureVersion: null, realtimeFeatureComputedAt: null } });
      setDiscoverMode("search");
      appendExposureEvents(hits, { requestId, traceId: requestId, surface: "SEARCH" });
      showToast(hits.length ? `找到 ${hits.length} 条视觉相似内容` : "没有找到视觉相似内容");
    } catch (error) {
      const message = error instanceof Error ? error.message : "多模态搜索失败";
      setDiscoverError(message);
      showToast(message, true);
    } finally { setBusy(null); }
  }

  async function runFeed(cursor?: string | null, append = false, targetUserId = savedUserId, targetSeed = feedSeed, activate = true) {
    setBusy("feed");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ page_size: "12" });
      if (targetSeed.trim()) params.set("seed_content_id", targetSeed.trim());
      if (cursor) params.set("cursor", cursor);
      const data = await api<FeedResponse>(`/api/bridge/online/v1/feed?${params}`, { headers: { "X-User-Id": targetUserId || "anonymous" } });
      const startPosition = append ? feedItems.length : 0;
      setFeedData(data);
      setFeedItems((current) => append ? [...current, ...data.items] : data.items);
      if (activate) setDiscoverMode("feed");
      setHealth((current) => ({ ...current, online: "online" }));
      appendExposureEvents(data.items, { requestId: data.requestId, traceId: data.requestId, surface: "FEED" }, startPosition);
      if (!data.items.length) showToast("当前画像暂无匹配内容");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Feed 生成失败";
      setDiscoverError(message);
      setHealth((current) => ({ ...current, online: "offline" }));
      showToast(message, true);
    } finally { setBusy(null); }
  }

  async function runSimilar(contentSeed: string) {
    setFeedSeed(contentSeed);
    setBusy("feed");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ page_size: "12" });
      const data = await api<FeedResponse>(`/api/bridge/online/v1/contents/${encodeURIComponent(contentSeed)}/similar?${params}`, { headers: { "X-User-Id": savedUserId || "anonymous" } });
      setFeedData(data);
      setFeedItems(data.items);
      setDiscoverMode("feed");
      appendExposureEvents(data.items, { requestId: data.requestId, traceId: data.requestId, surface: "FEED" });
      showToast("已切换为相似内容 Feed");
    } catch (error) {
      const message = error instanceof Error ? error.message : "相似内容召回失败";
      setDiscoverError(message);
      showToast(message, true);
    } finally { setBusy(null); }
  }

  async function runAgentSearch(targetQuery = agentQuery) {
    const cleanQuery = targetQuery.trim();
    if (!cleanQuery) return showToast("请描述你想找的内容", true);
    if (busy === "agent-search") return;

    const sessionId = agentSessionId || `session_${createEventId()}`;
    const turnId = `turn_${createEventId()}`;
    const turn: AgentTurn = { id: turnId, query: cleanQuery, status: "loading" };
    const controller = new AbortController();
    agentAbortRef.current = controller;
    setAgentSessionId(sessionId);
    setAgentQuery("");
    setAgentTurns((current) => [...current, turn]);
    setDiscoverMode("agent");
    setDiscoverError("");
    setBusy("agent-search");

    try {
      const response = await api<AgentSearchResponse>("/api/bridge/agent/v1/agent/search", {
        method: "POST",
        signal: controller.signal,
        body: JSON.stringify({
          requestId: `request_${createEventId()}`,
          sessionId,
          turnId,
          agentId: "search-assistant",
          mode: "AGENT",
          query: cleanQuery,
          page: 0,
          size: 12,
          requiredTags: [],
          ...(agentGoalVersion > 0 ? {
            constraintPatch: {
              baseVersion: agentGoalVersion,
              replacementQuery: cleanQuery,
              page: 0,
              size: 12,
              addRequiredTags: [],
              removeRequiredTags: [],
            },
          } : {}),
          options: { allowClarification: true },
        }),
      });
      setAgentGoalVersion(response.goalVersion);
      setAgentTurns((current) => current.map((item) => item.id === turnId
        ? { ...item, status: response.state === "CANCELLED" ? "cancelled" : "ready", response }
        : item));
      setHealth((current) => ({ ...current, agent: "online" }));
      if (response.items.length) appendExposureEvents(response.items, {
        requestId: response.requestId,
        traceId: response.agentRunId ?? response.requestId,
        surface: "AGENT",
      });
      if (response.degraded) showToast("智能搜索已降级，但保留了可用结果");
    } catch (error) {
      const cancelled = error instanceof DOMException && error.name === "AbortError";
      const message = cancelled ? "这次搜索已停止。" : error instanceof Error ? error.message : "AI 搜索失败";
      setAgentTurns((current) => current.map((item) => item.id === turnId
        ? { ...item, status: cancelled ? "cancelled" : "error", error: message }
        : item));
      if (!cancelled) {
        setHealth((current) => ({ ...current, agent: "offline" }));
        showToast(message, true);
      }
    } finally {
      if (agentAbortRef.current === controller) agentAbortRef.current = null;
      setBusy((current) => current === "agent-search" ? null : current);
    }
  }

  async function cancelAgentSearch() {
    if (!agentSessionId || !agentAbortRef.current) return;
    const controller = agentAbortRef.current;
    try {
      await api(`/api/bridge/agent/v1/agent/sessions/${encodeURIComponent(agentSessionId)}:cancel`, { method: "POST" });
    } catch {
      // Aborting the browser request still restores an interactive UI if the cancel signal cannot be delivered.
    } finally {
      controller.abort();
    }
  }

  function startNewAgentSession() {
    if (busy === "agent-search") return;
    setAgentSessionId("");
    setAgentGoalVersion(0);
    setAgentTurns([]);
    setAgentQuery("");
    setDiscoverMode("agent");
    window.setTimeout(() => document.getElementById("agent-search-input")?.focus(), 0);
  }

  async function saveViewerProfile() {
    const cleanUserId = userId.trim();
    if (!cleanUserId) return showToast("请先填写用户 ID", true);
    setBusy("profile-save");
    try {
      const profile = await api<UserInterestResponse>(
        `/api/bridge/online/v1/users/${encodeURIComponent(cleanUserId)}/interest-profile`,
        { method: "PUT", body: JSON.stringify({ topics: splitTags(interests) }) },
      );
      const normalizedInterests = profile.topics.join(", ");
      setUserId(profile.userId);
      setSavedUserId(profile.userId);
      setInterests(normalizedInterests);
      setProfileSavedAt(profile.updatedAt);
      setFeedSeed("");
      window.localStorage.setItem("seekflux.viewer-profile", JSON.stringify({
        userId: profile.userId, interests: normalizedInterests, savedAt: profile.updatedAt,
      }));
      await runFeed(null, false, profile.userId, "");
      showToast("画像已保存，推荐内容已刷新");
    } catch (error) {
      showToast(error instanceof Error ? error.message : "画像保存失败", true);
    } finally { setBusy(null); }
  }

  async function syncInteractions() {
    if (!events.length) return showToast("反馈队列还是空的");
    setBusy("sync");
    setSyncMessage("正在尝试提交 Interaction API…");
    try {
      const receipt = await api<InteractionBatchReceipt>("/api/bridge/online/v1/interactions:batch", {
        method: "POST",
        headers: { "Idempotency-Key": `web-${createEventId()}`, "X-User-Id": savedUserId || "anonymous" },
        body: JSON.stringify({ events: [...events].reverse() }),
      });
      const count = events.length;
      setEvents([]);
      setSyncMessage(`已回传 ${count} 个事件：接收 ${receipt.acceptedCount}，重复 ${receipt.duplicateCount}，拒绝 ${receipt.rejectedCount}。`);
      showToast(`${receipt.acceptedCount} 个行为事实已进入可靠链路`);
      for (let attempt = 0; attempt < 12; attempt += 1) {
        const snapshot = await loadRealtimeInterest(savedUserId || "anonymous", true);
        if (snapshot?.status === "FRESH") break;
        await new Promise((resolve) => window.setTimeout(resolve, 300));
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : "回传失败";
      setSyncMessage(`回传失败：${message}。事件仍保留在本地。`);
      showToast("行为回传失败，队列已保留", true);
    } finally { setBusy(null); }
  }

  async function loadRealtimeInterest(targetUserId = savedUserId, silent = false) {
    try {
      const snapshot = await api<ShortTermInterestFeatureResponse>(
        `/api/bridge/online/v1/features/users/${encodeURIComponent(targetUserId || "anonymous")}/short-term-interest`,
      );
      setRealtimeInterest(snapshot);
      return snapshot;
    } catch (error) {
      if (!silent) showToast(error instanceof Error ? error.message : "短期兴趣读取失败", true);
      return null;
    }
  }

  const displayedItems = discoverMode === "search" ? searchData?.hits ?? [] : feedItems;
  const consumerItems = displayedItems;
  const activeAttribution: InteractionAttribution = discoverMode === "feed"
    ? { requestId: feedData?.requestId ?? "feed_pending", traceId: feedData?.requestId ?? "feed_pending", surface: "FEED" }
    : { requestId: searchData?.trace.requestId ?? "search_pending", traceId: searchData?.trace.requestId ?? "search_pending", surface: "SEARCH" };
  const currentStage = content ? statusIndex[content.status] : 0;
  const eventCounts = useMemo(() => {
    const exposures = events.filter((event) => event.eventType === "EXPOSURE").length;
    return { exposures, actions: events.length - exposures };
  }, [events]);

  return (
    <div className={`app-shell workspace-${workspace}`}>
      {busy && <div className="loading-bar" aria-label="处理中" />}
      {workspace === "discover" ? (
        <ConsumerSidebar
          mode={discoverMode}
          setMode={setDiscoverMode}
          runFeed={runFeed}
          navigate={navigate}
          userId={userId}
          busy={busy}
        />
      ) : (
        <aside className="sidebar">
          <button className="brand-lockup" onClick={() => navigate("discover")} aria-label="回到发现页">
            <span className="brand-mark" aria-hidden="true">S</span>
            <span><strong className="brand-name">SeekFlux</strong><small className="brand-subtitle">Search to discovery</small></span>
          </button>
          <nav className="side-nav" aria-label="产品导航">
            {navItems.map((item) => (
              <button key={item.key} className={`side-nav-button ${workspace === item.key ? "active" : ""}`} onClick={() => navigate(item.key)}>
                <span className="nav-number">{item.number}</span>
                <span className="nav-copy"><strong>{item.title}</strong><small>{item.subtitle}</small></span>
                <span className="nav-role">{item.role}</span>
              </button>
            ))}
          </nav>
          <button className="side-return" onClick={() => navigate("discover")}><Icon name="play" /><span>打开发现页</span></button>
        </aside>
      )}

      {workspace === "discover" ? (
        <div className="consumer-mobile-header">
          <button className="consumer-mobile-brand" onClick={() => navigate("discover")}><span className="consumer-brand-mark">S</span><strong>SeekFlux</strong></button>
          <div><button onClick={() => setDiscoverMode("agent")}>AI 搜索</button><button onClick={() => navigate("audience")}>画像</button><button onClick={() => navigate("studio")}>投稿</button></div>
        </div>
      ) : (
        <div className="mobile-header">
          <button className="mobile-brand" onClick={() => navigate("discover")}><span className="brand-mark">S</span><strong>SeekFlux</strong></button>
          <div className="mobile-tabs">
            {navItems.map((item) => <button key={item.key} className={workspace === item.key ? "active" : ""} onClick={() => navigate(item.key)}>{item.title}</button>)}
          </div>
        </div>
      )}

      <main className="main-stage">
        {workspace !== "discover" && (
          <header className="topbar">
            <div><span className="surface-badge">B 端</span><span className="topbar-path">SeekFlux / <strong>{navItems.find((item) => item.key === workspace)?.title}</strong></span></div>
            <div className="service-cluster" aria-label="后端服务状态"><ServicePill name="Content" state={health.content} /><ServicePill name="Online" state={health.online} /><ServicePill name="Agent" state={health.agent} /></div>
          </header>
        )}

        {workspace === "discover" && (
          <DiscoverWorkspace
            mode={discoverMode} setMode={setDiscoverMode} userId={savedUserId}
            query={query} setQuery={setQuery} runSearch={runSearch} searchPage={searchPage} searchData={searchData}
            runFeed={runFeed} runSimilar={runSimilar} feedData={feedData} items={consumerItems}
            runMultimodalSearch={runMultimodalSearch}
            error={discoverError} busy={busy}
            attribution={activeAttribution} addInteraction={addInteraction} goProfile={() => navigate("audience")}
            agentQuery={agentQuery} setAgentQuery={setAgentQuery} agentTurns={agentTurns}
            runAgentSearch={runAgentSearch} cancelAgentSearch={cancelAgentSearch}
            startNewAgentSession={startNewAgentSession} agentHealth={health.agent}
          />
        )}

        {workspace === "audience" && (
          <AudienceWorkspace
            userId={userId} setUserId={setUserId} interests={interests} setInterests={setInterests}
            profileSavedAt={profileSavedAt} saveViewerProfile={saveViewerProfile} events={events}
            eventCounts={eventCounts} syncMessage={syncMessage} syncInteractions={syncInteractions}
            realtimeInterest={realtimeInterest} refreshRealtimeInterest={() => loadRealtimeInterest(savedUserId)}
            clearEvents={() => { setEvents([]); setSyncMessage("本地反馈队列已清空。"); }}
            busy={busy} goDiscover={() => navigate("discover")}
          />
        )}

        {workspace === "studio" && (
          <StudioWorkspace
            creatorId={creatorId} setCreatorId={setCreatorId} title={title} setTitle={setTitle}
            description={description} setDescription={setDescription} sourceTags={sourceTags} setSourceTags={setSourceTags}
            contentType={contentType} setContentType={setContentType} contentBody={contentBody} setContentBody={setContentBody}
            mediaUri={mediaUri} setMediaUri={setMediaUri} selectedFile={selectedFile} setSelectedFile={setSelectedFile}
            submitContent={submitContent} contentId={contentId} setContentId={setContentId} content={content}
            contentMessage={contentMessage} currentStage={currentStage} loadContent={loadContent} pollContent={pollContent}
            profileVersion={profileVersion} setProfileVersion={setProfileVersion} profileSummary={profileSummary}
            setProfileSummary={setProfileSummary} profileTags={profileTags} setProfileTags={setProfileTags}
            transcript={transcript} setTranscript={setTranscript} publishProfile={publishProfile}
            withdrawContent={withdrawContent} busy={busy} goDiscover={() => navigate("discover")}
          />
        )}
      </main>
      {toast && <div className={`toast ${toast.error ? "error" : ""}`} role="status">{toast.text}</div>}
    </div>
  );
}

function ConsumerSidebar({ mode, setMode, runFeed, navigate, userId, busy }: {
  mode: DiscoverMode;
  setMode: (mode: DiscoverMode) => void;
  runFeed: (cursor?: string | null, append?: boolean) => Promise<void>;
  navigate: (workspace: Workspace) => void;
  userId: string;
  busy: string | null;
}) {
  function focusSearch() {
    setMode("search");
    window.setTimeout(() => document.getElementById("consumer-search-input")?.focus(), 0);
  }
  return (
    <aside className="consumer-sidebar">
      <button className="consumer-brand" onClick={() => navigate("discover")} aria-label="SeekFlux 首页">
        <span className="consumer-brand-mark">S</span><span><strong>SeekFlux</strong><small>发现每一种兴趣</small></span>
      </button>
      <nav className="consumer-side-nav" aria-label="发现页导航">
        <button className={mode === "feed" ? "active" : ""} onClick={() => void runFeed()} disabled={busy === "feed"}><Icon name="play" /><span>推荐</span></button>
        <button className={mode === "search" ? "active" : ""} onClick={focusSearch}><Icon name="search" /><span>搜索</span></button>
        <button className={mode === "agent" ? "active" : ""} onClick={() => setMode("agent")}><Icon name="spark" /><span>AI 搜索</span></button>
        <div className="consumer-nav-divider" />
        <button onClick={() => navigate("audience")}><Icon name="pulse" /><span>用户画像</span></button>
        <button onClick={() => navigate("studio")}><Icon name="upload" /><span>内容工作台</span></button>
      </nav>
      <button className="consumer-account" onClick={() => navigate("audience")}>
        <span className="avatar">{userId.slice(0, 1).toUpperCase() || "U"}</span><span><strong>{userId || "未登录用户"}</strong><small>查看我的兴趣</small></span>
      </button>
    </aside>
  );
}

function ServicePill({ name, state }: { name: string; state: HealthState }) {
  const label = state === "checking" ? "检测中" : state === "online" ? "已连接" : "未连接";
  return <div className={`service-pill ${state}`} title={`${name} API ${label}`}><i />{name} · {label}</div>;
}

type DiscoverProps = {
  mode: DiscoverMode; setMode: (mode: DiscoverMode) => void; userId: string;
  query: string; setQuery: (value: string) => void; runSearch: (page?: number, query?: string) => Promise<void>;
  searchPage: number; searchData: SearchResponse | null; runFeed: (cursor?: string | null, append?: boolean) => Promise<void>;
  runSimilar: (contentId: string) => Promise<void>; feedData: FeedResponse | null; items: ContentItem[];
  runMultimodalSearch: (file: File) => Promise<void>;
  error: string; busy: string | null; attribution: InteractionAttribution;
  addInteraction: (type: EventType, item: ContentItem, position: number, attribution: InteractionAttribution) => void;
  goProfile: () => void;
  agentQuery: string; setAgentQuery: (value: string) => void; agentTurns: AgentTurn[];
  runAgentSearch: (query?: string) => Promise<void>; cancelAgentSearch: () => Promise<void>;
  startNewAgentSession: () => void; agentHealth: HealthState;
};

function DiscoverWorkspace(props: DiscoverProps) {
  if (props.mode === "agent") {
    return <AgentSearchWorkspace {...props} />;
  }
  const resultMeta = props.mode === "search" && props.searchData
    ? `${props.searchData.total} 条结果 · ${props.searchData.tookMillis} ms`
    : props.feedData ? `${props.feedData.items.length} 条推荐` : "为你推荐";
  const categories = ["推荐", "露营", "亲子", "咖啡", "旅行", "摄影", "知识", "科技", "生活", "美食"];

  function selectCategory(category: string) {
    if (category === "推荐") {
      props.setMode("feed");
      void props.runFeed();
      return;
    }
    props.setQuery(category);
    void props.runSearch(0, category);
  }

  return (
    <div className="consumer-home">
      <header className="consumer-home-header">
        <form className="consumer-home-search" onSubmit={(event) => { event.preventDefault(); void props.runSearch(0); }}>
          <Icon name="search" />
          <input id="consumer-search-input" value={props.query} onChange={(event) => props.setQuery(event.target.value)} placeholder="搜索你感兴趣的内容" aria-label="搜索内容" />
          <label className="media-query-button" title="上传图片或视频搜索">
            <input type="file" accept="image/*,video/*" onChange={(event) => { const file = event.target.files?.[0]; if (file) void props.runMultimodalSearch(file); event.currentTarget.value = ""; }} />
            <Icon name="upload" /><span>搜图/视频</span>
          </label>
          <button disabled={props.busy === "search"}><Icon name="search" /> 搜索</button>
        </form>
        <div className="consumer-header-actions">
          <button title="刷新推荐" onClick={() => void props.runFeed()} disabled={props.busy === "feed"}><Icon name="refresh" /></button>
          <button className="consumer-profile-button" onClick={props.goProfile}><span className="avatar">{props.userId.slice(0, 1).toUpperCase() || "U"}</span><span>我的</span></button>
        </div>
      </header>

      <nav className="consumer-category-tabs" aria-label="内容频道">
        {categories.map((category) => {
          const active = category === "推荐" ? props.mode === "feed" : props.mode === "search" && props.query === category;
          return <button key={category} className={active ? "active" : ""} onClick={() => selectCategory(category)}>{category}</button>;
        })}
      </nav>

      {props.error && <div className="consumer-service-note"><Icon name="info" /><span>暂时无法刷新内容，先看看这些推荐。</span></div>}
      {props.feedData?.degraded && props.mode === "feed" && <div className="consumer-service-note"><Icon name="info" /><span>部分内容源暂时不可用，已展示其余推荐。</span></div>}
      {props.searchData?.trace.degraded && props.mode === "search" && <div className="consumer-service-note"><Icon name="info" /><span>部分搜索通道暂时不可用，已展示可用结果。</span></div>}

      <div className="consumer-result-heading">
        <strong>{props.mode === "search" ? `“${props.query}”` : "精选推荐"}</strong>
        <span>{resultMeta}</span>
      </div>

      <section className="consumer-card-grid" aria-label={props.mode === "search" ? "搜索结果" : "推荐内容"}>
        {props.items.map((item, index) => (
          <ConsumerContentCard key={`${item.contentId}-${index}`} item={item} index={index} attribution={props.attribution} addInteraction={props.addInteraction} runSimilar={props.runSimilar} />
        ))}
      </section>
      {!props.items.length && !props.busy && (
        <div className="consumer-empty">
          <Icon name="search" />
          <strong>{props.mode === "feed" ? "当前画像暂无匹配内容" : "没有找到相关内容"}</strong>
          <p>{props.mode === "feed" ? "调整用户画像，或先在内容工作台发布带有对应标签的内容。" : "换一个关键词再试试。"}</p>
          {props.mode === "feed" && <button onClick={props.goProfile}>调整用户画像 <Icon name="arrow" /></button>}
        </div>
      )}

      <div className="consumer-pagination">
        {props.mode === "search" && props.searchData && props.searchData.total > props.searchData.size && (
          <>
            <button disabled={props.searchPage === 0 || props.busy === "search"} onClick={() => void props.runSearch(props.searchPage - 1)}><Icon name="left" /> 上一页</button>
            <span>第 {props.searchPage + 1} 页</span>
            <button disabled={(props.searchPage + 1) * props.searchData.size >= props.searchData.total || props.busy === "search"} onClick={() => void props.runSearch(props.searchPage + 1)}>下一页 <Icon name="arrow" /></button>
          </>
        )}
        {props.mode === "feed" && props.feedData?.nextCursor && <button onClick={() => void props.runFeed(props.feedData?.nextCursor, true)} disabled={props.busy === "feed"}>加载更多 <Icon name="down" /></button>}
      </div>
    </div>
  );
}

function AgentSearchWorkspace(props: DiscoverProps) {
  const examples = [
    "帮我找适合亲子周末的杭州露营内容",
    "只看咖啡和摄影相关的内容",
    "我想看科技类视频，最好和 AI 有关",
  ];
  const running = props.busy === "agent-search";

  function submit(event: FormEvent) {
    event.preventDefault();
    void props.runAgentSearch();
  }

  return (
    <div className="consumer-home agent-search-home">
      <header className="agent-search-header">
        <div className="agent-search-title">
          <span className="agent-orb"><Icon name="spark" /></span>
          <span><strong>AI 搜索</strong><small>多轮筛选内容</small></span>
          <i className={`agent-health ${props.agentHealth}`} title={`Agent ${props.agentHealth}`} />
        </div>
        <div className="agent-header-actions">
          <button onClick={props.startNewAgentSession} disabled={running}><Icon name="refresh" /> 新对话</button>
          <button className="consumer-profile-button" onClick={props.goProfile}><span className="avatar">{props.userId.slice(0, 1).toUpperCase() || "U"}</span><span>我的</span></button>
        </div>
      </header>

      <section className={`agent-conversation ${props.agentTurns.length ? "has-turns" : ""}`} aria-label="AI 搜索对话">
        {!props.agentTurns.length && (
          <div className="agent-welcome">
            <span className="agent-welcome-mark"><Icon name="spark" /></span>
            <h1>想找什么内容？</h1>
            <p>描述主题和条件，我会调用真实搜索能力筛选已发布内容。</p>
            <div className="agent-example-list">
              {examples.map((example) => <button key={example} onClick={() => void props.runAgentSearch(example)}>{example}<Icon name="arrow" /></button>)}
            </div>
          </div>
        )}

        {props.agentTurns.map((turn) => (
          <div className="agent-turn" key={turn.id}>
            <div className="agent-user-row"><div className="agent-user-bubble">{turn.query}</div><span className="avatar">{props.userId.slice(0, 1).toUpperCase() || "U"}</span></div>
            <div className="agent-assistant-row">
              <span className="agent-message-mark"><Icon name="spark" /></span>
              <div className="agent-assistant-body">
                {turn.status === "loading" && (
                  <div className="agent-thinking"><span /><span /><span /><em>正在理解条件并检索内容</em></div>
                )}
                {turn.status === "cancelled" && <p className="agent-reply">这次搜索已停止。</p>}
                {turn.status === "error" && (
                  <div className="agent-error"><p>{turn.error}</p><button onClick={() => props.setAgentQuery(turn.query)}>重新编辑</button></div>
                )}
                {turn.status === "ready" && turn.response && (
                  <>
                    <p className="agent-reply">{agentReply(turn.response)}</p>
                    <div className="agent-result-meta">
                      <span>{turn.response.executionMode === "AGENT" ? "Agent 检索" : "稳定搜索"}</span>
                      {turn.response.appliedConstraints.requiredTags.map((tag) => <span key={tag}>#{tag}</span>)}
                      {turn.response.agentTrace && <span>{turn.response.agentTrace.tookMillis} ms</span>}
                      {turn.response.agentTrace?.usageMeasured && <span>{turn.response.agentTrace.totalTokens} tokens</span>}
                    </div>
                    {turn.response.items.length > 0 && (
                      <section className="consumer-card-grid agent-result-grid" aria-label="AI 搜索结果">
                        {turn.response.items.map((item, index) => (
                          <ConsumerContentCard
                            key={`${turn.id}-${item.contentId}-${index}`}
                            item={item}
                            index={index}
                            attribution={{
                              requestId: turn.response?.requestId ?? "agent_pending",
                              traceId: turn.response?.agentRunId ?? turn.response?.requestId ?? "agent_pending",
                              surface: "AGENT",
                            }}
                            addInteraction={props.addInteraction}
                            runSimilar={props.runSimilar}
                          />
                        ))}
                      </section>
                    )}
                  </>
                )}
              </div>
            </div>
          </div>
        ))}
      </section>

      <div className="agent-composer-shell">
        <form className="agent-composer" onSubmit={submit}>
          <Icon name="spark" />
          <textarea
            id="agent-search-input"
            rows={1}
            value={props.agentQuery}
            onChange={(event) => props.setAgentQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                if (!running) void props.runAgentSearch();
              }
            }}
            placeholder={props.agentTurns.length ? "继续补充条件…" : "描述你想找的视频或图文…"}
            aria-label="描述搜索需求"
            maxLength={500}
          />
          {running
            ? <button type="button" className="agent-stop" onClick={() => void props.cancelAgentSearch()} aria-label="停止搜索"><span /></button>
            : <button className="agent-send" disabled={!props.agentQuery.trim()} aria-label="发送搜索需求"><Icon name="arrow" /></button>}
        </form>
        <small>Enter 发送 · Shift + Enter 换行</small>
      </div>
    </div>
  );
}

function ConsumerContentCard({ item, index, attribution, addInteraction, runSimilar }: {
  item: ContentItem; index: number; attribution: InteractionAttribution;
  addInteraction: DiscoverProps["addInteraction"]; runSimilar: (contentId: string) => Promise<void>;
}) {
  const [mediaError, setMediaError] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const isArticle = item.contentType === "ARTICLE";
  const canRender = Boolean(item.mediaUri) && !mediaError;
  const assets = item.assetUris?.length ? item.assetUris : [item.mediaUri];
  const durations = ["08:21", "05:48", "10:06", "06:32", "04:19", "07:45", "09:12", "03:56", "11:08"];
  const likes = ["2.7万", "1.2万", "8,462", "3.1万", "9,830", "1.8万", "6,521", "2.2万", "7,104"];
  const posterWords = ["去野", "手冲", "亲子", "川西", "晚餐", "日落", "散步", "AI", "好好住"];
  return (
    <article className="consumer-content-card">
      <div className={`consumer-card-cover palette-${index % 5}`}>
        {canRender && isArticle ? (
          <button className="consumer-article-cover" onClick={() => { setDetailOpen(true); addInteraction("CLICK", item, index + 1, attribution); }} aria-label={`打开图文：${item.title}`}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={item.mediaUri} alt={item.title} onError={() => setMediaError(true)} />
          </button>
        ) : canRender ? (
          // Remote creator media does not yet expose a captions track in the content contract.
          // eslint-disable-next-line jsx-a11y/media-has-caption
          <video src={item.mediaUri} controls playsInline preload="metadata" onError={() => setMediaError(true)} onPlay={() => addInteraction("PLAY_START", item, index + 1, attribution)} />
        ) : (
          <div className={`consumer-poster poster-${index % 9}`}>
            <span className="poster-kicker">SEEKFLUX PICKS</span>
            <strong>{posterWords[index % posterWords.length]}</strong>
            <small>{item.tags.slice(0, 2).join(" · ")}</small>
          </div>
        )}
        <div className="consumer-cover-label">{isArticle ? "图文" : item.sources?.[0] === "INTEREST" ? "猜你喜欢" : item.tags[0] || "视频"}</div>
        <div className="consumer-cover-meta"><button title="喜欢" onClick={() => addInteraction("LIKE", item, index + 1, attribution)}><Icon name="heart" /> {likes[index % likes.length]}</button><span>{isArticle ? `${assets.length} 图` : durations[index % durations.length]}</span></div>
      </div>
      <div className="consumer-card-copy">
        <button className="consumer-card-title" onClick={() => { setDetailOpen(true); addInteraction("CLICK", item, index + 1, attribution); }}>{item.title}</button>
        <div className="consumer-card-tags">{item.tags.slice(0, 3).map((tag) => <span key={tag}>#{tag}</span>)}</div>
        <div className="consumer-card-byline">
          <span>@{item.creatorId} · {index % 3 === 0 ? "3天前" : index % 3 === 1 ? "6小时前" : "昨天"}</span>
          <div>
            <button title="查看相似内容" onClick={() => void runSimilar(item.contentId)}><Icon name="layers" /></button>
            <button title="减少此类内容" onClick={() => addInteraction("NOT_INTERESTED", item, index + 1, attribution)}><Icon name="hide" /></button>
          </div>
        </div>
      </div>
      {detailOpen && (
        <div className="content-detail-backdrop" role="presentation" onMouseDown={() => setDetailOpen(false)}>
          <section className="content-detail" role="dialog" aria-modal="true" aria-label={item.title} onMouseDown={(event) => event.stopPropagation()}>
            <button className="content-detail-close" onClick={() => setDetailOpen(false)} aria-label="关闭详情">×</button>
            <div className={`content-detail-media ${isArticle ? "article-assets" : ""}`}>
              {isArticle ? assets.map((asset, assetIndex) => (
                // eslint-disable-next-line @next/next/no-img-element
                <img key={asset} src={asset} alt={`${item.title} ${assetIndex + 1}`} />
              )) : (
                // eslint-disable-next-line jsx-a11y/media-has-caption
                <video src={item.mediaUri} controls autoPlay playsInline onPlay={() => addInteraction("PLAY_START", item, index + 1, attribution)} />
              )}
            </div>
            <div className="content-detail-copy">
              <span className="content-kind">{isArticle ? "图文" : "视频"}</span>
              <h1>{item.title}</h1>
              <p className="content-detail-byline">@{item.sourceAuthor || item.creatorId}</p>
              <div className="content-detail-tags">{item.tags.map((tag) => <span key={tag}>#{tag}</span>)}</div>
              <p className="content-detail-body">{item.body || item.description || item.summary}</p>
              {(item.sourceProvider || item.sourcePageUri) && <div className="content-provenance"><strong>来源</strong><span>{item.sourceProvider || "外部内容"}{item.licenseName ? ` · ${item.licenseName}` : ""}</span>{item.sourcePageUri && <a href={item.sourcePageUri} target="_blank" rel="noreferrer">查看原始页面</a>}</div>}
              <div className="content-detail-actions"><button onClick={() => addInteraction("LIKE", item, index + 1, attribution)}><Icon name="heart" /> 喜欢</button><button onClick={() => void runSimilar(item.contentId)}><Icon name="layers" /> 相似内容</button></div>
            </div>
          </section>
        </div>
      )}
    </article>
  );
}

type AudienceProps = {
  userId: string; setUserId: (value: string) => void; interests: string; setInterests: (value: string) => void;
  profileSavedAt: string; saveViewerProfile: () => Promise<void>; events: InteractionEvent[];
  eventCounts: { exposures: number; actions: number }; syncMessage: string; syncInteractions: () => Promise<void>;
  realtimeInterest: ShortTermInterestFeatureResponse | null; refreshRealtimeInterest: () => Promise<ShortTermInterestFeatureResponse | null>;
  clearEvents: () => void; busy: string | null; goDiscover: () => void;
};

function AudienceWorkspace(props: AudienceProps) {
  const interestList = splitTags(props.interests);
  const quickInterests = ["露营", "亲子", "咖啡", "摄影", "旅行", "科技"];
  const hasEvents = props.events.length > 0;
  const eventsSynced = props.syncMessage.startsWith("已回传");
  const tasks: TaskGuideItem[] = [
    { title: "设置兴趣", detail: props.profileSavedAt ? "冷启动画像已保存" : "填写用户 ID 并选择兴趣", state: props.profileSavedAt ? "done" : "active" },
    { title: "采集行为", detail: eventsSynced ? "行为已采集并回传" : hasEvents ? `已记录 ${props.events.length} 条事件` : "去发现页浏览并产生互动", state: eventsSynced || hasEvents ? "done" : props.profileSavedAt ? "active" : "pending" },
    { title: "回传信号", detail: eventsSynced ? "事件已提交" : hasEvents ? "将本地队列提交给后端" : "等待行为事件", state: eventsSynced ? "done" : hasEvents ? "active" : "pending" },
  ];
  function toggleInterest(value: string) {
    const next = interestList.includes(value) ? interestList.filter((item) => item !== value) : [...interestList, value];
    props.setInterests(next.join(", "));
  }
  return (
    <>
      <section className="operator-header">
        <div><span className="operator-label">用户运营</span><h1>用户画像</h1><p>设置兴趣 <i /> 采集行为 <i /> 回传信号</p></div>
        <button className="button secondary" onClick={props.goDiscover}>去发现页采集行为 <Icon name="arrow" /></button>
      </section>
      <TaskGuide items={tasks} />

      <section className="audience-grid">
        <article className="panel profile-console">
          <div className="panel-header"><div><div className="panel-kicker">步骤 1</div><h2>设置身份与兴趣</h2></div><span className="status-tag beta">当前设备</span></div>
          <div className="panel-body">
            <div className="profile-identity"><span className="avatar large">{props.userId.slice(0, 1).toUpperCase() || "U"}</span><div><strong>{props.userId || "anonymous"}</strong><small>用于生成第一轮推荐</small></div></div>
            <label><span className="field-label">用户 ID <span>X-User-Id</span></span><input className="input" value={props.userId} onChange={(event) => props.setUserId(event.target.value)} placeholder="demo-user" /></label>
            <label><span className="field-label">显式兴趣 <span>逗号分隔</span></span><input className="input" value={props.interests} onChange={(event) => props.setInterests(event.target.value)} placeholder="露营, 亲子" /></label>
            <div className="interest-picker">{quickInterests.map((item) => <button key={item} className={interestList.includes(item) ? "selected" : ""} onClick={() => toggleInterest(item)}>{interestList.includes(item) ? "✓ " : "+ "}{item}</button>)}</div>
            <div className="form-actions"><button className="button accent" disabled={props.busy === "profile-save"} onClick={() => void props.saveViewerProfile()}>保存画像并刷新推荐</button><span className="save-note">{props.profileSavedAt ? `保存于 ${formatEventTime(props.profileSavedAt)}` : "尚未保存"}</span></div>
            <div className="boundary-note"><Icon name="info" /><span>画像保存到推荐服务，发现页只返回标签匹配内容。</span></div>
          </div>
        </article>

        <article className="signal-card">
          <div className="signal-head"><div><div className="panel-kicker">步骤 2</div><h2>行为信号</h2></div><button className="icon-button" title="刷新短期兴趣" onClick={() => void props.refreshRealtimeInterest()}><Icon name="refresh" /></button></div>
          <div className="signal-stats"><div><strong>{props.eventCounts.exposures}</strong><span>曝光</span></div><div><strong>{props.eventCounts.actions}</strong><span>主动行为</span></div><div><strong>{new Set(props.events.map((event) => event.contentId)).size}</strong><span>内容数</span></div></div>
          <div className="signal-map"><span>曝光</span><i /><span>互动</span><i /><span className={props.realtimeInterest?.status === "FRESH" ? "" : "planned"}>兴趣</span><i /><span className={props.realtimeInterest?.status === "FRESH" ? "" : "planned"}>推荐</span></div>
          {props.realtimeInterest?.status === "FRESH" ? (
            <div className="realtime-interest"><div><strong>短期兴趣</strong><small>{props.realtimeInterest.featureVersion} · {props.realtimeInterest.computedAt ? formatEventTime(props.realtimeInterest.computedAt) : ""}</small></div><div>{props.realtimeInterest.topics.length ? props.realtimeInterest.topics.map((topic) => <span key={topic.topic}>{topic.topic} {topic.score.toFixed(2)}</span>) : <span>暂无正向主题</span>}</div></div>
          ) : <p>{hasEvents ? "已有可回传信号，请在下方检查事件队列。" : "去发现页浏览、查看相似内容或减少不感兴趣内容。"}</p>}
        </article>

        <article className="panel queue-console">
          <div className="panel-header"><div><div className="panel-kicker">步骤 3</div><h2>回传行为事件</h2></div><span className="status-tag planned">本地队列</span></div>
          <div className="queue-list">
            {props.events.length ? props.events.slice(0, 40).map((event) => (
              <div className="queue-item" key={event.eventId}><span className="event-type">{event.eventType}</span><span className="event-content">{shortId(event.contentId)}</span><span className="event-position">#{event.position}</span><span className="event-time">{formatEventTime(event.eventTime)}</span></div>
            )) : <EmptyState symbol="0" title="暂无行为事件" text="先去发现页浏览内容，事件会自动出现在这里。" />}
          </div>
          <div className="queue-footer"><div className="result-banner">{props.syncMessage}</div><div className="form-actions"><button className="button accent" disabled={!props.events.length || props.busy === "sync"} onClick={() => void props.syncInteractions()}>回传队列</button><button className="button ghost" disabled={!props.events.length} onClick={props.clearEvents}>清空队列</button></div></div>
        </article>
      </section>
    </>
  );
}

type StudioProps = {
  creatorId: string; setCreatorId: (value: string) => void; title: string; setTitle: (value: string) => void;
  description: string; setDescription: (value: string) => void; sourceTags: string; setSourceTags: (value: string) => void;
  contentType: "VIDEO" | "ARTICLE"; setContentType: (value: "VIDEO" | "ARTICLE") => void;
  contentBody: string; setContentBody: (value: string) => void;
  mediaUri: string; setMediaUri: (value: string) => void; selectedFile: File | null; setSelectedFile: (value: File | null) => void;
  submitContent: (event: FormEvent) => void; contentId: string; setContentId: (value: string) => void;
  content: ContentResponse | null; contentMessage: string; currentStage: number; loadContent: () => Promise<ContentResponse | null>;
  pollContent: () => Promise<void>; profileVersion: number; setProfileVersion: (value: number) => void;
  profileSummary: string; setProfileSummary: (value: string) => void; profileTags: string; setProfileTags: (value: string) => void;
  transcript: string; setTranscript: (value: string) => void; publishProfile: (event: FormEvent) => void;
  withdrawContent: () => Promise<void>; busy: string | null; goDiscover: () => void;
};

function StudioWorkspace(props: StudioProps) {
  const pipeline = [["内容登记", "保存标题与媒体地址"], ["任务投递", "进入异步处理队列"], ["画像生成", "生成摘要与标签"], ["质量检查", "确认画像版本"], ["索引发布", "进入搜索和推荐"]];
  const hasContent = Boolean(props.contentId);
  const profileReady = props.content?.status === "PROFILE_READY" || props.content?.status === "PUBLISHED";
  const published = props.content?.status === "PUBLISHED";
  const tasks: TaskGuideItem[] = [
    { title: "登记内容", detail: hasContent ? "已生成内容 ID" : "填写媒体地址和标题", state: hasContent ? "done" : "active" },
    { title: "查看处理", detail: profileReady ? "画像已生成" : hasContent ? "查询或等待处理状态" : "等待内容登记", state: profileReady ? "done" : hasContent ? "active" : "pending" },
    { title: "校准发布", detail: published ? "内容已进入发现页" : profileReady ? "检查标签与摘要后发布" : "等待画像生成", state: published ? "done" : profileReady ? "active" : "pending" },
  ];
  return (
    <>
      <section className="operator-header">
        <div><span className="operator-label">创作者中心</span><h1>内容工作台</h1><p>登记内容 <i /> 查看处理 <i /> 校准发布</p></div>
        <button className="button secondary" onClick={props.goDiscover}>查看发布结果 <Icon name="arrow" /></button>
      </section>
      <TaskGuide items={tasks} />

      <section className="studio-grid">
        <article className="panel upload-console">
          <div className="panel-header"><div><div className="panel-kicker">步骤 1</div><h2>登记新内容</h2></div><span className="status-tag">接口可用</span></div>
          <form className="panel-body" onSubmit={props.submitContent}>
            <label className="upload-dropzone">
              <input type="file" accept={props.contentType === "VIDEO" ? "video/*" : "image/*"} onChange={(event) => props.setSelectedFile(event.target.files?.[0] ?? null)} />
              <span className="upload-icon"><Icon name="upload" /></span>
              <strong>{props.selectedFile?.name || (props.contentType === "VIDEO" ? "选择一个视频文件" : "选择图文封面")}</strong>
              <small>{props.selectedFile ? "提交时将上传到媒体存储并自动分析" : "也可以在下方填写已有媒体地址"}</small>
            </label>
            <div className="field-grid">
              <label><span className="field-label">内容类型</span><select className="input" value={props.contentType} onChange={(event) => props.setContentType(event.target.value as "VIDEO" | "ARTICLE")}><option value="VIDEO">视频</option><option value="ARTICLE">图文</option></select></label>
              <label><span className="field-label">创建者</span><input className="input" value={props.creatorId} onChange={(event) => props.setCreatorId(event.target.value)} required maxLength={128} /></label>
              <label className="wide"><span className="field-label">媒体地址 <span>与本地文件二选一</span></span><input className="input" value={props.mediaUri} onChange={(event) => props.setMediaUri(event.target.value)} placeholder="S3 / HTTPS URI" /></label>
              <label className="wide"><span className="field-label">内容标题 <span>必填</span></span><input className="input" value={props.title} onChange={(event) => props.setTitle(event.target.value)} required maxLength={200} /></label>
              <label className="wide"><span className="field-label">内容描述 <span>用于基础画像</span></span><textarea className="textarea" value={props.description} onChange={(event) => props.setDescription(event.target.value)} maxLength={4000} /></label>
              {props.contentType === "ARTICLE" && <label className="wide"><span className="field-label">图文正文 <span>详情页与检索</span></span><textarea className="textarea article-body-input" value={props.contentBody} onChange={(event) => props.setContentBody(event.target.value)} maxLength={100000} placeholder="输入图文正文…" /></label>}
              <label><span className="field-label">来源标签 <span>初始推荐匹配</span></span><input className="input" value={props.sourceTags} onChange={(event) => props.setSourceTags(event.target.value)} /></label>
            </div>
            <div className="form-actions"><button className="button accent" disabled={props.busy === "content-submit"}>登记并开始处理 <Icon name="arrow" /></button><button type="button" className="button secondary" onClick={() => { props.setContentType(sampleContent.contentType); props.setContentBody(sampleContent.body); props.setTitle(sampleContent.title); props.setDescription(sampleContent.description); props.setCreatorId(sampleContent.creatorId); props.setSourceTags(sampleContent.tags); props.setMediaUri(sampleContent.mediaUri); }}>载入示例</button></div>
          </form>
        </article>

        <article className="pipeline-console">
          <div className="pipeline-head"><div><div className="panel-kicker">步骤 2</div><h2>查看处理状态</h2></div><span className={`content-state ${props.content?.status.toLowerCase() ?? "idle"}`}>{props.content?.status ?? "IDLE"}</span></div>
          <div className="content-id-box"><label htmlFor="content-id">内容 ID</label><input id="content-id" value={props.contentId} onChange={(event) => props.setContentId(event.target.value)} placeholder="登记后自动填入，也可粘贴已有 ID" /></div>
          <div className="pipeline">
            {pipeline.map(([name, detail], index) => {
              const state = index < props.currentStage ? "done" : index === props.currentStage && props.content ? "current" : "";
              return <div className={`pipeline-step ${state}`} key={name}><span className="pipeline-node">{state === "done" ? "✓" : index + 1}</span><span className="pipeline-copy"><strong>{name}</strong><small>{detail}</small></span><span className="pipeline-state">{state === "done" ? "done" : state === "current" ? "active" : "waiting"}</span></div>;
            })}
          </div>
          <div className="pipeline-actions"><button className="button secondary" onClick={() => void props.loadContent()} disabled={!props.contentId || props.busy === "content-load"}>查询状态</button><button className="button mint" onClick={() => void props.pollContent()} disabled={!props.contentId || props.busy === "content-poll"}>等待发布</button></div>
          <div className={`result-banner dark ${props.content?.status === "PUBLISHED" ? "success" : ""}`}>{props.contentMessage}</div>
        </article>

        <article className="panel profile-editor">
          <div className="panel-header"><div><div className="panel-kicker">步骤 3</div><h2>校准并发布画像</h2></div><span className="status-tag">{hasContent ? "可编辑" : "等待登记"}</span></div>
          <div className="profile-shell">
            <div className="editor-checklist">
              <strong>发布前检查</strong>
              <span className={hasContent ? "done" : ""}><i>{hasContent ? "✓" : "1"}</i> 已关联内容 ID</span>
              <span className={props.profileSummary && props.profileTags ? "done" : ""}><i>{props.profileSummary && props.profileTags ? "✓" : "2"}</i> 已补充标签与摘要</span>
              <span className={published ? "done" : ""}><i>{published ? "✓" : "3"}</i> 发布到搜索和推荐</span>
            </div>
            <form onSubmit={props.publishProfile}>
              <div className="field-grid">
                <label><span className="field-label">画像版本</span><input className="input" type="number" min={1} value={props.profileVersion} onChange={(event) => props.setProfileVersion(Number(event.target.value))} /></label>
                <label><span className="field-label">画像标签 <span>决定推荐人群</span></span><input className="input" value={props.profileTags} onChange={(event) => props.setProfileTags(event.target.value)} /></label>
                <label className="wide"><span className="field-label">画像摘要</span><textarea className="textarea" value={props.profileSummary} onChange={(event) => props.setProfileSummary(event.target.value)} required /></label>
                <label className="wide"><span className="field-label">ASR 转写 <span>当前可选</span></span><textarea className="textarea" value={props.transcript} onChange={(event) => props.setTranscript(event.target.value)} placeholder="粘贴语音转写文本，用于检索召回…" /></label>
              </div>
              <div className="form-actions"><button className="button" disabled={!props.contentId || props.busy === "profile-publish"}>保存画像并发布</button><button type="button" className="button danger-ghost" disabled={!props.contentId || props.busy === "content-withdraw"} onClick={() => void props.withdrawContent()}>撤回内容</button></div>
            </form>
          </div>
        </article>
      </section>
    </>
  );
}

type TaskGuideItem = { title: string; detail: string; state: "done" | "active" | "pending" };

function TaskGuide({ items }: { items: TaskGuideItem[] }) {
  return (
    <ol className="task-guide" aria-label="操作步骤">
      {items.map((item, index) => (
        <li className={item.state} key={item.title}>
          <span className="task-number">{item.state === "done" ? "✓" : index + 1}</span>
          <span><strong>{item.title}</strong><small>{item.detail}</small></span>
        </li>
      ))}
    </ol>
  );
}

function EmptyState({ symbol, title, text }: { symbol: string; title: string; text: string }) {
  return <div className="empty-state"><div className="empty-symbol">{symbol}</div><strong>{title}</strong><p>{text}</p></div>;
}

type IconName = "arrow" | "left" | "down" | "play" | "search" | "refresh" | "info" | "heart" | "layers" | "hide" | "spark" | "pulse" | "upload";

function Icon({ name }: { name: IconName }) {
  const paths: Record<IconName, React.ReactNode> = {
    arrow: <><path d="M5 12h14" /><path d="m13 6 6 6-6 6" /></>,
    left: <><path d="M19 12H5" /><path d="m11 18-6-6 6-6" /></>,
    down: <><path d="M12 5v14" /><path d="m18 13-6 6-6-6" /></>,
    play: <path d="m8 5 11 7-11 7Z" />,
    search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></>,
    refresh: <><path d="M20 6v5h-5" /><path d="M4 18v-5h5" /><path d="M6.1 9a7 7 0 0 1 11.5-2.6L20 11" /><path d="m4 13 2.4 4.6A7 7 0 0 0 18 15" /></>,
    info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v5" /><path d="M12 8h.01" /></>,
    heart: <path d="M20.8 5.8a5.5 5.5 0 0 0-7.8 0L12 6.8l-1-1A5.5 5.5 0 0 0 3.2 13L12 21l8.8-8a5.5 5.5 0 0 0 0-7.2Z" />,
    layers: <><path d="m12 2 9 5-9 5-9-5 9-5Z" /><path d="m3 12 9 5 9-5" /><path d="m3 17 9 5 9-5" /></>,
    hide: <><path d="M3 3l18 18" /><path d="M10.7 10.7a2 2 0 0 0 2.6 2.6" /><path d="M9.9 4.2A10.5 10.5 0 0 1 12 4c7 0 10 8 10 8a16 16 0 0 1-2.1 3.3" /><path d="M6.2 6.2C3.5 8 2 12 2 12s3 8 10 8a10 10 0 0 0 4.1-.9" /></>,
    spark: <><path d="m12 3 1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5L12 3Z" /><path d="m19 15 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z" /></>,
    pulse: <path d="M3 12h4l2-7 4 14 2-7h6" />,
    upload: <><path d="M12 16V3" /><path d="m7 8 5-5 5 5" /><path d="M5 13v7h14v-7" /></>,
  };
  return <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}
