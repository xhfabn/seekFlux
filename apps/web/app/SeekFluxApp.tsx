"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

type Workspace = "discover" | "audience" | "studio";
type DiscoverMode = "feed" | "search";
type HealthState = "checking" | "online" | "offline";
type EventType = "EXPOSURE" | "PLAY_START" | "LIKE" | "NOT_INTERESTED";

type ContentProfile = {
  version: number;
  summary: string;
  tags: string[];
  transcript: string;
};

type ContentResponse = {
  contentId: string;
  creatorId: string;
  mediaUri: string;
  title: string;
  description: string;
  sourceTags: string[];
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
  mediaUri: string;
  title: string;
  description: string;
  summary: string;
  tags: string[];
  profileVersion: number;
  score: number;
  publishedAt: string;
  sources?: string[];
  reason?: string;
  placeholder?: boolean;
};

type SearchResponse = {
  query: string;
  total: number;
  page: number;
  size: number;
  tookMillis: number;
  hits: ContentItem[];
};

type FeedResponse = {
  requestId: string;
  items: ContentItem[];
  nextCursor: string | null;
  degraded: boolean;
  unavailableSources: string[];
};

type InteractionEvent = {
  eventId: string;
  eventType: EventType;
  requestId: string;
  contentId: string;
  position: number;
  eventTime: string;
};

const sampleContent = {
  creatorId: "seekflux-demo",
  title: "杭州周末露营路线",
  description: "从市区出发的一日露营与日落路线，适合新手和亲子家庭。",
  tags: "露营, 杭州, 周末, 亲子",
  mediaUri: "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
};

const placeholderItems: ContentItem[] = [
  {
    contentId: "preview_first_video",
    creatorId: "你的创作者账号",
    mediaUri: "",
    title: "第一条视频会在这里开始播放",
    description: "内容发布后，推荐 Feed 会把真实视频、作者信息和推荐理由装进这张卡片。",
    summary: "当前保留完整的视频消费位，等待对象存储和媒体转码链路接入。",
    tags: ["视频占位", "推荐流", "待发布"],
    profileVersion: 0,
    score: 0,
    publishedAt: "",
    sources: ["FEED_SHELL"],
    reason: "产品结构已就绪 · 暂无已发布视频",
    placeholder: true,
  },
  {
    contentId: "preview_search_to_feed",
    creatorId: "SeekFlux",
    mediaUri: "",
    title: "搜索之后，继续刷相似内容",
    description: "搜索命中的内容可以直接成为 Item-Item 召回种子，继续生成相似 Feed。",
    summary: "C 端把搜索、推荐和相似内容串成一条连续消费路径。",
    tags: ["主动搜索", "相似召回", "连续发现"],
    profileVersion: 0,
    score: 0,
    publishedAt: "",
    sources: ["SEARCH", "SIMILAR"],
    reason: "交互占位 · 接口已经接入",
    placeholder: true,
  },
  {
    contentId: "preview_feedback_loop",
    creatorId: "SeekFlux",
    mediaUri: "",
    title: "每一次喜欢，都会成为下一次信号",
    description: "曝光、播放、喜欢和不感兴趣会先进入本地事件队列，等待 Interaction API 接棒。",
    summary: "反馈结构已保留，后续可以无缝接入实时兴趣与排序。",
    tags: ["行为反馈", "实时兴趣", "架构占位"],
    profileVersion: 0,
    score: 0,
    publishedAt: "",
    sources: ["LOCAL_SIGNAL"],
    reason: "行为链路占位 · 数据不会伪报成功",
    placeholder: true,
  },
];

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
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return `evt_${crypto.randomUUID()}`;
  return `evt_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

async function api<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      ...(options?.body ? { "Content-Type": "application/json" } : {}),
      ...options?.headers,
    },
  });
  const payload = (await response.json().catch(() => ({}))) as { message?: string } & T;
  if (!response.ok) throw new Error(payload.message || `${response.status} ${response.statusText}`);
  return payload;
}

function shortId(value: string): string {
  return value.length <= 20 ? value : `${value.slice(0, 9)}…${value.slice(-6)}`;
}

function formatEventTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value));
}

export function SeekFluxApp() {
  const [workspace, setWorkspace] = useState<Workspace>("discover");
  const [discoverMode, setDiscoverMode] = useState<DiscoverMode>("feed");
  const [busy, setBusy] = useState<string | null>(null);
  const [toast, setToast] = useState<{ text: string; error?: boolean } | null>(null);
  const [health, setHealth] = useState<Record<"content" | "online", HealthState>>({ content: "checking", online: "checking" });

  const [creatorId, setCreatorId] = useState(sampleContent.creatorId);
  const [title, setTitle] = useState(sampleContent.title);
  const [description, setDescription] = useState(sampleContent.description);
  const [sourceTags, setSourceTags] = useState(sampleContent.tags);
  const [mediaUri, setMediaUri] = useState(sampleContent.mediaUri);
  const [selectedFile, setSelectedFile] = useState("");
  const [contentId, setContentId] = useState("");
  const [content, setContent] = useState<ContentResponse | null>(null);
  const [contentMessage, setContentMessage] = useState("等待登记一条内容");
  const [profileVersion, setProfileVersion] = useState(2);
  const [profileSummary, setProfileSummary] = useState("适合亲子家庭和露营新手的杭州周边周末路线，包含日落观景建议。");
  const [profileTags, setProfileTags] = useState("亲子露营, 杭州周边, 新手路线");
  const [transcript, setTranscript] = useState("");

  const [userId, setUserId] = useState("demo-user");
  const [interests, setInterests] = useState("露营, 亲子");
  const [profileSavedAt, setProfileSavedAt] = useState("");
  const [query, setQuery] = useState("杭州 周末 露营");
  const [searchPage, setSearchPage] = useState(0);
  const [searchData, setSearchData] = useState<SearchResponse | null>(null);
  const [feedSeed, setFeedSeed] = useState("");
  const [feedData, setFeedData] = useState<FeedResponse | null>(null);
  const [feedItems, setFeedItems] = useState<ContentItem[]>([]);
  const [discoverError, setDiscoverError] = useState("");

  const [events, setEvents] = useState<InteractionEvent[]>([]);
  const [queueHydrated, setQueueHydrated] = useState(false);
  const [syncMessage, setSyncMessage] = useState("Interaction API 尚未完成；行为先安全保存在当前浏览器。 ");

  const showToast = useCallback((text: string, error = false) => setToast({ text, error }), []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 3600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    const rawEvents = window.localStorage.getItem("seekflux.interactions");
    if (rawEvents) {
      try { setEvents(JSON.parse(rawEvents) as InteractionEvent[]); } catch { window.localStorage.removeItem("seekflux.interactions"); }
    }
    const rawProfile = window.localStorage.getItem("seekflux.viewer-profile");
    if (rawProfile) {
      try {
        const saved = JSON.parse(rawProfile) as { userId?: string; interests?: string; savedAt?: string };
        if (saved.userId) setUserId(saved.userId);
        if (saved.interests) setInterests(saved.interests);
        if (saved.savedAt) setProfileSavedAt(saved.savedAt);
      } catch { window.localStorage.removeItem("seekflux.viewer-profile"); }
    }
    setQueueHydrated(true);
  }, []);

  useEffect(() => {
    if (queueHydrated) window.localStorage.setItem("seekflux.interactions", JSON.stringify(events));
  }, [events, queueHydrated]);

  useEffect(() => {
    let cancelled = false;
    async function check(service: "content" | "online") {
      try {
        await api(`/api/bridge/${service}/actuator/health`);
        if (!cancelled) setHealth((current) => ({ ...current, [service]: "online" }));
      } catch {
        if (!cancelled) setHealth((current) => ({ ...current, [service]: "offline" }));
      }
    }
    void check("content");
    void check("online");
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
      const data = await api<ContentResponse>("/api/bridge/content/v1/contents", {
        method: "POST",
        body: JSON.stringify({ creatorId, mediaUri, title, description, sourceTags: splitTags(sourceTags) }),
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

  const appendExposureEvents = useCallback((items: ContentItem[], requestId: string, startPosition = 0) => {
    const now = new Date().toISOString();
    setEvents((current) => {
      const next = items.map((item, index) => ({
        eventId: createEventId(), eventType: "EXPOSURE" as const, requestId,
        contentId: item.contentId, position: startPosition + index + 1, eventTime: now,
      }));
      return [...next, ...current].slice(0, 200);
    });
  }, []);

  const addInteraction = useCallback((eventType: EventType, item: ContentItem, position: number, requestId: string) => {
    const nextEvent: InteractionEvent = {
      eventId: createEventId(), eventType, requestId, contentId: item.contentId,
      position, eventTime: new Date().toISOString(),
    };
    setEvents((current) => [nextEvent, ...current].slice(0, 200));
    showToast(item.placeholder ? "演示行为已保存到本地画像" : "行为已保存到反馈队列");
  }, [showToast]);

  async function runSearch(targetPage = 0, targetQuery = query) {
    const cleanQuery = targetQuery.trim();
    if (!cleanQuery) return showToast("请输入搜索词", true);
    setBusy("search");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ q: cleanQuery, page: String(targetPage), size: "12" });
      const data = await api<SearchResponse>(`/api/bridge/online/v1/search?${params}`);
      setQuery(cleanQuery);
      setSearchPage(targetPage);
      setSearchData(data);
      setDiscoverMode("search");
      setHealth((current) => ({ ...current, online: "online" }));
      appendExposureEvents(data.hits, `search_${createEventId()}`);
      if (!data.hits.length) showToast("没有匹配结果，已保留产品占位内容");
    } catch (error) {
      const message = error instanceof Error ? error.message : "搜索失败";
      setDiscoverError(message);
      setHealth((current) => ({ ...current, online: "offline" }));
      showToast(message, true);
    } finally { setBusy(null); }
  }

  async function runFeed(cursor?: string | null, append = false) {
    setBusy("feed");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ page_size: "12" });
      if (interests.trim()) params.set("interests", interests.trim());
      if (feedSeed.trim()) params.set("seed_content_id", feedSeed.trim());
      if (cursor) params.set("cursor", cursor);
      const data = await api<FeedResponse>(`/api/bridge/online/v1/feed?${params}`, { headers: { "X-User-Id": userId || "anonymous" } });
      const startPosition = append ? feedItems.length : 0;
      setFeedData(data);
      setFeedItems((current) => append ? [...current, ...data.items] : data.items);
      setDiscoverMode("feed");
      setHealth((current) => ({ ...current, online: "online" }));
      appendExposureEvents(data.items, data.requestId, startPosition);
      if (!data.items.length) showToast("Feed 暂无真实内容，继续展示视频占位结构");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Feed 生成失败";
      setDiscoverError(message);
      setHealth((current) => ({ ...current, online: "offline" }));
      showToast(message, true);
    } finally { setBusy(null); }
  }

  async function runSimilar(contentSeed: string) {
    if (contentSeed.startsWith("preview_")) return showToast("发布真实内容后即可调用相似召回");
    setFeedSeed(contentSeed);
    setBusy("feed");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ page_size: "12" });
      if (interests.trim()) params.set("interests", interests.trim());
      const data = await api<FeedResponse>(`/api/bridge/online/v1/contents/${encodeURIComponent(contentSeed)}/similar?${params}`, { headers: { "X-User-Id": userId || "anonymous" } });
      setFeedData(data);
      setFeedItems(data.items);
      setDiscoverMode("feed");
      appendExposureEvents(data.items, data.requestId);
      showToast("已切换为相似内容 Feed");
    } catch (error) {
      const message = error instanceof Error ? error.message : "相似内容召回失败";
      setDiscoverError(message);
      showToast(message, true);
    } finally { setBusy(null); }
  }

  function saveViewerProfile() {
    const savedAt = new Date().toISOString();
    window.localStorage.setItem("seekflux.viewer-profile", JSON.stringify({ userId, interests, savedAt }));
    setProfileSavedAt(savedAt);
    showToast("冷启动画像已保存到当前设备");
  }

  async function syncInteractions() {
    if (!events.length) return showToast("反馈队列还是空的");
    setBusy("sync");
    setSyncMessage("正在尝试提交 Interaction API…");
    try {
      await api("/api/bridge/online/v1/interactions:batch", {
        method: "POST",
        headers: { "Idempotency-Key": `batch_${createEventId()}` },
        body: JSON.stringify({ events: [...events].reverse() }),
      });
      const count = events.length;
      setEvents([]);
      setSyncMessage(`已成功回传 ${count} 个事件，等待实时特征链路消费。`);
      showToast(`${count} 个反馈事件已回传`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "回传失败";
      setSyncMessage(`后端尚未接通：${message}。事件仍保留在本地。`);
      showToast("Interaction API 尚未可用，队列已保留", true);
    } finally { setBusy(null); }
  }

  const displayedItems = discoverMode === "search" ? searchData?.hits ?? [] : feedItems;
  const consumerItems = displayedItems.length ? displayedItems : placeholderItems;
  const activeRequestId = discoverMode === "feed" ? feedData?.requestId ?? "feed_preview" : "search_frontend";
  const currentStage = content ? statusIndex[content.status] : 0;
  const eventCounts = useMemo(() => {
    const exposures = events.filter((event) => event.eventType === "EXPOSURE").length;
    return { exposures, actions: events.length - exposures };
  }, [events]);

  return (
    <div className={`app-shell workspace-${workspace}`}>
      {busy && <div className="loading-bar" aria-label="处理中" />}
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
        <div className="side-status">
          <div className="side-status-title"><span className="pulse-dot" /> 当前实现边界</div>
          <p>搜索、推荐和内容画像已接真实接口；媒体上传与实时行为回流保留清晰占位。</p>
        </div>
        <div className="side-version">PRODUCT SHELL / STEP 3</div>
      </aside>

      <div className="mobile-header">
        <button className="mobile-brand" onClick={() => navigate("discover")}><span className="brand-mark">S</span><strong>SeekFlux</strong></button>
        <div className="mobile-tabs">
          {navItems.map((item) => <button key={item.key} className={workspace === item.key ? "active" : ""} onClick={() => navigate(item.key)}>{item.title}</button>)}
        </div>
      </div>

      <main className="main-stage">
        <header className="topbar">
          <div><span className="surface-badge">{workspace === "discover" ? "C 端" : "B 端"}</span><span className="topbar-path">SeekFlux / <strong>{navItems.find((item) => item.key === workspace)?.title}</strong></span></div>
          <div className="service-cluster" aria-label="后端服务状态"><ServicePill name="Content" state={health.content} /><ServicePill name="Online" state={health.online} /></div>
        </header>

        {workspace === "discover" && (
          <DiscoverWorkspace
            mode={discoverMode} setMode={setDiscoverMode} userId={userId} interests={interests}
            query={query} setQuery={setQuery} runSearch={runSearch} searchPage={searchPage} searchData={searchData}
            runFeed={runFeed} runSimilar={runSimilar} feedData={feedData} items={consumerItems}
            usingPlaceholders={!displayedItems.length} error={discoverError} busy={busy}
            requestId={activeRequestId} addInteraction={addInteraction} goProfile={() => navigate("audience")}
          />
        )}

        {workspace === "audience" && (
          <AudienceWorkspace
            userId={userId} setUserId={setUserId} interests={interests} setInterests={setInterests}
            profileSavedAt={profileSavedAt} saveViewerProfile={saveViewerProfile} events={events}
            eventCounts={eventCounts} syncMessage={syncMessage} syncInteractions={syncInteractions}
            clearEvents={() => { setEvents([]); setSyncMessage("本地反馈队列已清空。"); }}
            busy={busy} goDiscover={() => navigate("discover")}
          />
        )}

        {workspace === "studio" && (
          <StudioWorkspace
            creatorId={creatorId} setCreatorId={setCreatorId} title={title} setTitle={setTitle}
            description={description} setDescription={setDescription} sourceTags={sourceTags} setSourceTags={setSourceTags}
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

function ServicePill({ name, state }: { name: string; state: HealthState }) {
  const label = state === "checking" ? "检测中" : state === "online" ? "已连接" : "未连接";
  return <div className={`service-pill ${state}`} title={`${name} API ${label}`}><i />{name} · {label}</div>;
}

type DiscoverProps = {
  mode: DiscoverMode; setMode: (mode: DiscoverMode) => void; userId: string; interests: string;
  query: string; setQuery: (value: string) => void; runSearch: (page?: number, query?: string) => Promise<void>;
  searchPage: number; searchData: SearchResponse | null; runFeed: (cursor?: string | null, append?: boolean) => Promise<void>;
  runSimilar: (contentId: string) => Promise<void>; feedData: FeedResponse | null; items: ContentItem[];
  usingPlaceholders: boolean; error: string; busy: string | null; requestId: string;
  addInteraction: (type: EventType, item: ContentItem, position: number, requestId: string) => void;
  goProfile: () => void;
};

function DiscoverWorkspace(props: DiscoverProps) {
  const resultMeta = props.mode === "search" && props.searchData
    ? `${props.searchData.total} 条结果 · ${props.searchData.tookMillis} ms`
    : props.feedData ? `${props.feedData.items.length} 条推荐` : "产品预览";

  function scrollToCard(index: number) {
    document.getElementById(`seekflux-card-${index}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  }

  return (
    <>
      <section className="consumer-intro">
        <div><div className="eyebrow">Discover / Consumer app</div><h1>搜到答案，<br />也发现<em>下一条。</em></h1></div>
        <div className="consumer-intro-copy">
          <p>这是面向普通用户的内容消费入口：主动搜索、个性化推荐和相似内容在同一条连续路径里完成。</p>
          <button className="text-link" onClick={props.goProfile}>调整我的兴趣画像 <Icon name="arrow" /></button>
        </div>
      </section>

      <section className="consumer-toolbar" aria-label="发现工具栏">
        <div className="mode-switcher" role="tablist">
          <button className={props.mode === "feed" ? "active" : ""} onClick={() => props.setMode("feed")}><Icon name="play" /> 推荐</button>
          <button className={props.mode === "search" ? "active" : ""} onClick={() => props.setMode("search")}><Icon name="search" /> 搜索</button>
        </div>
        <form className="consumer-search" onSubmit={(event) => { event.preventDefault(); void props.runSearch(0); }}>
          <Icon name="search" />
          <input value={props.query} onChange={(event) => props.setQuery(event.target.value)} placeholder="搜索视频、主题或一个问题" aria-label="搜索内容" />
          <button disabled={props.busy === "search"}>搜索</button>
        </form>
        <button className="icon-button refresh-button" title="根据当前画像刷新推荐" onClick={() => void props.runFeed()} disabled={props.busy === "feed"}><Icon name="refresh" /></button>
      </section>

      <div className="quick-topics" aria-label="热门搜索">
        {["杭州 周末 露营", "新手 手冲 咖啡", "上海 亲子 博物馆", "川西 自驾 摄影"].map((topic) => (
          <button key={topic} onClick={() => { props.setQuery(topic); void props.runSearch(0, topic); }}># {topic}</button>
        ))}
      </div>

      {props.error && <div className="connection-note"><Icon name="info" /><span>{props.error}。当前继续展示产品占位，后端恢复后可直接刷新。</span></div>}
      {props.feedData?.degraded && props.mode === "feed" && <div className="connection-note warning"><Icon name="info" /><span>部分召回源已降级：{props.feedData.unavailableSources.join("、")}，其余结果仍可消费。</span></div>}

      <section className="discover-layout">
        <div className="feed-phone" aria-label="纵向视频 Feed">
          <div className="feed-topline"><span>{props.mode === "feed" ? "为你推荐" : `“${props.query}”`}</span><small>{props.userId} · {resultMeta}</small></div>
          <div className="feed-scroller">
            {props.items.map((item, index) => (
              <MediaSlide key={`${item.contentId}-${index}`} item={item} index={index} requestId={props.requestId} addInteraction={props.addInteraction} runSimilar={props.runSimilar} />
            ))}
            {props.mode === "feed" && props.feedData?.nextCursor && (
              <div className="feed-more"><button className="button accent" onClick={() => void props.runFeed(props.feedData?.nextCursor, true)} disabled={props.busy === "feed"}>继续加载 <Icon name="down" /></button></div>
            )}
          </div>
        </div>

        <aside className="result-drawer">
          <div className="drawer-head"><div><span className="panel-kicker">Queue</span><h2>{props.mode === "search" ? "搜索结果" : "接下来"}</h2></div><span className="result-count">{props.usingPlaceholders ? "占位" : props.items.length}</span></div>
          <div className="viewer-profile-mini"><span className="avatar">{props.userId.slice(0, 1).toUpperCase() || "U"}</span><div><strong>{props.userId || "anonymous"}</strong><small>{props.interests || "暂无冷启动兴趣"}</small></div></div>
          <div className="result-list">
            {props.items.map((item, index) => (
              <button className="result-row" key={`${item.contentId}-row`} onClick={() => scrollToCard(index)}>
                <span className={`result-thumb palette-${index % 5}`}>{String(index + 1).padStart(2, "0")}</span>
                <span><strong>{item.title}</strong><small>{item.creatorId} · {item.tags.slice(0, 2).join(" / ")}</small></span>
                <Icon name="arrow" />
              </button>
            ))}
          </div>
          {props.mode === "search" && props.searchData && props.searchData.total > props.searchData.size && (
            <div className="drawer-pagination">
              <button title="上一页" disabled={props.searchPage === 0 || props.busy === "search"} onClick={() => void props.runSearch(props.searchPage - 1)}><Icon name="left" /></button>
              <span>{props.searchPage + 1}</span>
              <button title="下一页" disabled={(props.searchPage + 1) * props.searchData.size >= props.searchData.total || props.busy === "search"} onClick={() => void props.runSearch(props.searchPage + 1)}><Icon name="arrow" /></button>
            </div>
          )}
        </aside>
      </section>
    </>
  );
}

function MediaSlide({ item, index, requestId, addInteraction, runSimilar }: {
  item: ContentItem; index: number; requestId: string;
  addInteraction: DiscoverProps["addInteraction"]; runSimilar: (contentId: string) => Promise<void>;
}) {
  const [mediaError, setMediaError] = useState(false);
  const canPlay = Boolean(item.mediaUri) && !item.placeholder && !mediaError;
  return (
    <article className="feed-slide" id={`seekflux-card-${index}`}>
      <div className={`video-stage palette-${index % 5}`}>
        {canPlay ? (
          <video src={item.mediaUri} controls playsInline preload="metadata" onError={() => setMediaError(true)} onPlay={() => addInteraction("PLAY_START", item, index + 1, requestId)} />
        ) : (
          <div className="video-placeholder">
            <span className="placeholder-orbit" aria-hidden="true" />
            <span className="play-mark"><Icon name="play" /></span>
            <strong>{item.placeholder ? "VIDEO SLOT" : "MEDIA PREVIEW"}</strong>
            <small>{item.placeholder ? "发布视频后在这里播放" : "媒体暂不可预览"}</small>
          </div>
        )}
        <div className="video-context">
          <div className="creator-line"><span>@{item.creatorId}</span>{item.placeholder && <small>结构占位</small>}</div>
          <h2>{item.title}</h2>
          <p>{item.summary || item.description}</p>
          <div className="video-tags">{item.tags.slice(0, 4).map((tag) => <span key={tag}>#{tag}</span>)}</div>
          {item.reason && <div className="recommend-reason"><Icon name="spark" /> {item.reason}</div>}
        </div>
        <div className="action-rail" aria-label="内容操作">
          <button title="喜欢" onClick={() => addInteraction("LIKE", item, index + 1, requestId)}><Icon name="heart" /><span>喜欢</span></button>
          <button title="查看相似内容" onClick={() => void runSimilar(item.contentId)}><Icon name="layers" /><span>相似</span></button>
          <button title="不感兴趣" onClick={() => addInteraction("NOT_INTERESTED", item, index + 1, requestId)}><Icon name="hide" /><span>减少</span></button>
        </div>
        <div className="slide-index">{String(index + 1).padStart(2, "0")}</div>
      </div>
    </article>
  );
}

type AudienceProps = {
  userId: string; setUserId: (value: string) => void; interests: string; setInterests: (value: string) => void;
  profileSavedAt: string; saveViewerProfile: () => void; events: InteractionEvent[];
  eventCounts: { exposures: number; actions: number }; syncMessage: string; syncInteractions: () => Promise<void>;
  clearEvents: () => void; busy: string | null; goDiscover: () => void;
};

function AudienceWorkspace(props: AudienceProps) {
  const interestList = splitTags(props.interests);
  const quickInterests = ["露营", "亲子", "咖啡", "摄影", "旅行", "科技"];
  function toggleInterest(value: string) {
    const next = interestList.includes(value) ? interestList.filter((item) => item !== value) : [...interestList, value];
    props.setInterests(next.join(", "));
  }
  return (
    <>
      <section className="admin-hero">
        <div><div className="eyebrow">Audience / Operator console</div><h1>理解用户，<br />但不假装<em>已经实时。</em></h1></div>
        <div className="admin-hero-copy"><p>这里管理冷启动兴趣和浏览器行为队列。真实用户画像服务、实时特征存储尚未实现，因此持久化边界会被明确标注。</p><button className="button secondary" onClick={props.goDiscover}>返回 C 端体验 <Icon name="arrow" /></button></div>
      </section>

      <section className="audience-grid">
        <article className="panel profile-console">
          <div className="panel-header"><div><div className="panel-kicker">Cold-start profile</div><h2>用户冷启动画像</h2></div><span className="status-tag beta">设备本地</span></div>
          <div className="panel-body">
            <div className="profile-identity"><span className="avatar large">{props.userId.slice(0, 1).toUpperCase() || "U"}</span><div><strong>{props.userId || "anonymous"}</strong><small>显式兴趣会作为 Feed 多路召回上下文</small></div></div>
            <label><span className="field-label">用户 ID <span>X-User-Id</span></span><input className="input" value={props.userId} onChange={(event) => props.setUserId(event.target.value)} placeholder="demo-user" /></label>
            <label><span className="field-label">显式兴趣 <span>逗号分隔</span></span><input className="input" value={props.interests} onChange={(event) => props.setInterests(event.target.value)} placeholder="露营, 亲子" /></label>
            <div className="interest-picker">{quickInterests.map((item) => <button key={item} className={interestList.includes(item) ? "selected" : ""} onClick={() => toggleInterest(item)}>{interestList.includes(item) ? "✓ " : "+ "}{item}</button>)}</div>
            <div className="form-actions"><button className="button accent" onClick={props.saveViewerProfile}>保存冷启动画像</button><span className="save-note">{props.profileSavedAt ? `上次保存 ${formatEventTime(props.profileSavedAt)}` : "尚未保存"}</span></div>
            <div className="boundary-note"><Icon name="info" /><span>当前写入 localStorage，不会伪装成服务端用户画像；接入 Profile / Feature API 后可替换此适配层。</span></div>
          </div>
        </article>

        <article className="signal-card">
          <div className="signal-head"><div><div className="panel-kicker">Behavior signal</div><h2>行为信号概览</h2></div><Icon name="pulse" /></div>
          <div className="signal-stats"><div><strong>{props.eventCounts.exposures}</strong><span>曝光</span></div><div><strong>{props.eventCounts.actions}</strong><span>主动行为</span></div><div><strong>{new Set(props.events.map((event) => event.contentId)).size}</strong><span>内容数</span></div></div>
          <div className="signal-map"><span>曝光</span><i /><span>互动</span><i /><span className="planned">实时兴趣</span><i /><span className="planned">再排序</span></div>
          <p>前两段已经由前端记录；后两段等待 Interaction API、Kafka / Flink 与在线特征存储。</p>
        </article>

        <article className="panel queue-console">
          <div className="panel-header"><div><div className="panel-kicker">Interaction buffer</div><h2>行为事件队列</h2></div><span className="status-tag planned">接口占位</span></div>
          <div className="queue-list">
            {props.events.length ? props.events.slice(0, 40).map((event) => (
              <div className="queue-item" key={event.eventId}><span className="event-type">{event.eventType}</span><span className="event-content">{shortId(event.contentId)}</span><span className="event-position">#{event.position}</span><span className="event-time">{formatEventTime(event.eventTime)}</span></div>
            )) : <EmptyState symbol="0" title="还没有行为信号" text="去 C 端发现页刷几条内容，曝光、喜欢与负反馈会进入这里。" />}
          </div>
          <div className="queue-footer"><div className="result-banner">{props.syncMessage}</div><div className="form-actions"><button className="button accent" disabled={!props.events.length || props.busy === "sync"} onClick={() => void props.syncInteractions()}>尝试批量回传</button><button className="button ghost" disabled={!props.events.length} onClick={props.clearEvents}>清空本地队列</button></div></div>
        </article>
      </section>
    </>
  );
}

type StudioProps = {
  creatorId: string; setCreatorId: (value: string) => void; title: string; setTitle: (value: string) => void;
  description: string; setDescription: (value: string) => void; sourceTags: string; setSourceTags: (value: string) => void;
  mediaUri: string; setMediaUri: (value: string) => void; selectedFile: string; setSelectedFile: (value: string) => void;
  submitContent: (event: FormEvent) => void; contentId: string; setContentId: (value: string) => void;
  content: ContentResponse | null; contentMessage: string; currentStage: number; loadContent: () => Promise<ContentResponse | null>;
  pollContent: () => Promise<void>; profileVersion: number; setProfileVersion: (value: number) => void;
  profileSummary: string; setProfileSummary: (value: string) => void; profileTags: string; setProfileTags: (value: string) => void;
  transcript: string; setTranscript: (value: string) => void; publishProfile: (event: FormEvent) => void;
  withdrawContent: () => Promise<void>; busy: string | null; goDiscover: () => void;
};

function StudioWorkspace(props: StudioProps) {
  const pipeline = [["内容登记", "PostgreSQL + Outbox"], ["任务投递", "Kafka"], ["画像生成", "Worker"], ["质量门禁", "Version check"], ["索引发布", "Elasticsearch"]];
  return (
    <>
      <section className="admin-hero">
        <div><div className="eyebrow">Content / Creator console</div><h1>把一条视频，<br />变成<em>可发现内容。</em></h1></div>
        <div className="admin-hero-copy"><p>这是创作者与内部运营使用的 B 端工作台：登记媒体、观察异步画像、人工校准，再发布到搜索与推荐索引。</p><button className="button secondary" onClick={props.goDiscover}>查看 C 端呈现 <Icon name="arrow" /></button></div>
      </section>

      <section className="studio-grid">
        <article className="panel upload-console">
          <div className="panel-header"><div><div className="panel-kicker">New content</div><h2>上传与登记</h2></div><span className="status-tag">Content API 已接通</span></div>
          <form className="panel-body" onSubmit={props.submitContent}>
            <label className="upload-dropzone">
              <input type="file" accept="video/*" onChange={(event) => props.setSelectedFile(event.target.files?.[0]?.name ?? "")} />
              <span className="upload-icon"><Icon name="upload" /></span>
              <strong>{props.selectedFile || "选择一个视频文件"}</strong>
              <small>{props.selectedFile ? "已建立本地选择占位；请继续填写可访问的媒体 URI" : "拖放体验位已保留 · 对象存储上传接口待接入"}</small>
            </label>
            <div className="field-grid">
              <label className="wide"><span className="field-label">媒体地址 <span>当前 API 必填</span></span><input className="input" value={props.mediaUri} onChange={(event) => props.setMediaUri(event.target.value)} required placeholder="S3 / HTTPS URI" /></label>
              <label className="wide"><span className="field-label">内容标题 <span>必填</span></span><input className="input" value={props.title} onChange={(event) => props.setTitle(event.target.value)} required maxLength={200} /></label>
              <label className="wide"><span className="field-label">内容描述 <span>用于基础画像</span></span><textarea className="textarea" value={props.description} onChange={(event) => props.setDescription(event.target.value)} maxLength={4000} /></label>
              <label><span className="field-label">创建者</span><input className="input" value={props.creatorId} onChange={(event) => props.setCreatorId(event.target.value)} required maxLength={128} /></label>
              <label><span className="field-label">来源标签 <span>逗号分隔</span></span><input className="input" value={props.sourceTags} onChange={(event) => props.setSourceTags(event.target.value)} /></label>
            </div>
            <div className="form-actions"><button className="button accent" disabled={props.busy === "content-submit"}>登记并开始处理 <Icon name="arrow" /></button><button type="button" className="button secondary" onClick={() => { props.setTitle(sampleContent.title); props.setDescription(sampleContent.description); props.setCreatorId(sampleContent.creatorId); props.setSourceTags(sampleContent.tags); props.setMediaUri(sampleContent.mediaUri); }}>载入示例</button></div>
          </form>
        </article>

        <article className="pipeline-console">
          <div className="pipeline-head"><div><div className="panel-kicker">Processing trace</div><h2>内容处理轨迹</h2></div><span className={`content-state ${props.content?.status.toLowerCase() ?? "idle"}`}>{props.content?.status ?? "IDLE"}</span></div>
          <div className="content-id-box"><label htmlFor="content-id">Content ID</label><input id="content-id" value={props.contentId} onChange={(event) => props.setContentId(event.target.value)} placeholder="登记后自动填入，也可粘贴已有 ID" /></div>
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
          <div className="panel-header"><div><div className="panel-kicker">Human in the loop</div><h2>内容画像校准</h2></div><span className="status-tag">版本化发布已接通</span></div>
          <div className="profile-shell">
            <div className="profile-intro"><div className="profile-preview palette-1"><Icon name="spark" /><span>PROFILE</span></div><h3>先校准，再进入搜索和推荐。</h3><p>ASR、OCR 与视觉理解接入前，可以手工填写摘要、标签与转写，复用同一份画像完成和发布契约。</p><code>PUT /profile → POST /publish</code></div>
            <form onSubmit={props.publishProfile}>
              <div className="field-grid">
                <label><span className="field-label">画像版本</span><input className="input" type="number" min={1} value={props.profileVersion} onChange={(event) => props.setProfileVersion(Number(event.target.value))} /></label>
                <label><span className="field-label">画像标签 <span>逗号分隔</span></span><input className="input" value={props.profileTags} onChange={(event) => props.setProfileTags(event.target.value)} /></label>
                <label className="wide"><span className="field-label">画像摘要</span><textarea className="textarea" value={props.profileSummary} onChange={(event) => props.setProfileSummary(event.target.value)} required /></label>
                <label className="wide"><span className="field-label">ASR 转写 <span>当前可选</span></span><textarea className="textarea" value={props.transcript} onChange={(event) => props.setTranscript(event.target.value)} placeholder="粘贴语音转写文本，用于检索召回…" /></label>
              </div>
              <div className="form-actions"><button className="button" disabled={!props.contentId || props.busy === "profile-publish"}>保存并发布</button><button type="button" className="button danger-ghost" disabled={!props.contentId || props.busy === "content-withdraw"} onClick={() => void props.withdrawContent()}>撤回内容</button></div>
            </form>
          </div>
        </article>
      </section>
    </>
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
