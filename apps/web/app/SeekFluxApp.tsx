"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

type Workspace = "content" | "discover" | "feedback";
type DiscoverMode = "search" | "feed";
type HealthState = "checking" | "online" | "offline";

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
  eventType: "EXPOSURE" | "PLAY_START" | "LIKE" | "NOT_INTERESTED";
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
  mediaUri:
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
};

const navItems: Array<{
  key: Workspace;
  number: string;
  title: string;
  subtitle: string;
}> = [
  { key: "content", number: "01", title: "内容中枢", subtitle: "登记 · 画像 · 发布" },
  { key: "discover", number: "02", title: "发现引擎", subtitle: "搜索 · Feed · 相似" },
  { key: "feedback", number: "03", title: "反馈回路", subtitle: "曝光 · 互动 · 回流" },
];

const statusIndex: Record<ContentResponse["status"], number> = {
  SUBMITTED: 1,
  PROFILE_READY: 3,
  PUBLISHED: 4,
  WITHDRAWN: 0,
};

function splitTags(value: string): string[] {
  return value
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter(Boolean);
}

function createEventId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `evt_${crypto.randomUUID()}`;
  }
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
  const payload = (await response.json().catch(() => ({}))) as {
    message?: string;
  } & T;
  if (!response.ok) {
    throw new Error(payload.message || `${response.status} ${response.statusText}`);
  }
  return payload;
}

function shortId(value: string): string {
  if (value.length <= 18) return value;
  return `${value.slice(0, 8)}…${value.slice(-6)}`;
}

function formatEventTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

export function SeekFluxApp() {
  const [workspace, setWorkspace] = useState<Workspace>("content");
  const [discoverMode, setDiscoverMode] = useState<DiscoverMode>("search");
  const [busy, setBusy] = useState<string | null>(null);
  const [toast, setToast] = useState<{ text: string; error?: boolean } | null>(null);
  const [health, setHealth] = useState<Record<"content" | "online", HealthState>>({
    content: "checking",
    online: "checking",
  });

  const [creatorId, setCreatorId] = useState(sampleContent.creatorId);
  const [title, setTitle] = useState(sampleContent.title);
  const [description, setDescription] = useState(sampleContent.description);
  const [sourceTags, setSourceTags] = useState(sampleContent.tags);
  const [mediaUri, setMediaUri] = useState(sampleContent.mediaUri);
  const [contentId, setContentId] = useState("");
  const [content, setContent] = useState<ContentResponse | null>(null);
  const [contentMessage, setContentMessage] = useState("等待登记一条内容");
  const [profileVersion, setProfileVersion] = useState(2);
  const [profileSummary, setProfileSummary] = useState(
    "适合亲子家庭和露营新手的杭州周边周末路线，包含日落观景建议。",
  );
  const [profileTags, setProfileTags] = useState("亲子露营, 杭州周边, 新手路线");
  const [transcript, setTranscript] = useState("");

  const [userId, setUserId] = useState("demo-user");
  const [interests, setInterests] = useState("露营, 亲子");
  const [query, setQuery] = useState("杭州 周末 露营");
  const [searchPage, setSearchPage] = useState(0);
  const [searchData, setSearchData] = useState<SearchResponse | null>(null);
  const [feedSeed, setFeedSeed] = useState("");
  const [feedData, setFeedData] = useState<FeedResponse | null>(null);
  const [feedItems, setFeedItems] = useState<ContentItem[]>([]);
  const [discoverError, setDiscoverError] = useState("");

  const [events, setEvents] = useState<InteractionEvent[]>([]);
  const [queueHydrated, setQueueHydrated] = useState(false);
  const [syncMessage, setSyncMessage] = useState(
    "当前先保存在浏览器；Interaction API 接通后可一键批量回传。",
  );

  const showToast = useCallback((text: string, error = false) => {
    setToast({ text, error });
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 3600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    const raw = window.localStorage.getItem("seekflux.interactions");
    if (raw) {
      try {
        setEvents(JSON.parse(raw) as InteractionEvent[]);
      } catch {
        window.localStorage.removeItem("seekflux.interactions");
      }
    }
    setQueueHydrated(true);
  }, []);

  useEffect(() => {
    if (!queueHydrated) return;
    window.localStorage.setItem("seekflux.interactions", JSON.stringify(events));
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
    return () => {
      cancelled = true;
    };
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
        body: JSON.stringify({
          creatorId,
          mediaUri,
          title,
          description,
          sourceTags: splitTags(sourceTags),
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
    } finally {
      setBusy(null);
    }
  }

  async function loadContent(silent = false): Promise<ContentResponse | null> {
    if (!contentId.trim()) {
      if (!silent) showToast("请先输入内容 ID", true);
      return null;
    }
    if (!silent) setBusy("content-load");
    try {
      const data = await api<ContentResponse>(
        `/api/bridge/content/v1/contents/${encodeURIComponent(contentId.trim())}`,
      );
      applyContentResponse(data);
      setContentMessage(
        `${data.status} · 聚合版本 v${data.version}${data.profile ? ` · 画像 v${data.profile.version}` : ""}`,
      );
      setHealth((current) => ({ ...current, content: "online" }));
      return data;
    } catch (error) {
      const message = error instanceof Error ? error.message : "查询失败";
      setContentMessage(message);
      if (!silent) showToast(message, true);
      return null;
    } finally {
      if (!silent) setBusy(null);
    }
  }

  async function pollContent() {
    setBusy("content-poll");
    setContentMessage("正在轮询异步处理进度…");
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const data = await loadContent(true);
      if (!data) break;
      if (data.status === "PUBLISHED") {
        setBusy(null);
        showToast("内容画像已发布，可以去搜索验证了");
        return;
      }
      await new Promise((resolve) => window.setTimeout(resolve, 1000));
    }
    setBusy(null);
    setContentMessage((current) =>
      `${current} · 等待超时，请确认 Worker、Kafka 与 Elasticsearch 已启动`,
    );
  }

  async function publishProfile(event: FormEvent) {
    event.preventDefault();
    if (!contentId.trim()) {
      showToast("请先登记或查询一条内容", true);
      return;
    }
    setBusy("profile-publish");
    try {
      await api<ContentResponse>(
        `/api/bridge/content/v1/contents/${encodeURIComponent(contentId.trim())}/profile`,
        {
          method: "PUT",
          body: JSON.stringify({
            profileVersion,
            summary: profileSummary,
            tags: splitTags(profileTags),
            transcript,
          }),
        },
      );
      const published = await api<ContentResponse>(
        `/api/bridge/content/v1/contents/${encodeURIComponent(contentId.trim())}/publish`,
        { method: "POST" },
      );
      applyContentResponse(published);
      setContentMessage(
        `PUBLISHED · 聚合版本 v${published.version} · 画像 v${published.profile?.version ?? profileVersion}`,
      );
      showToast("画像已完成校准并重新发布");
    } catch (error) {
      showToast(error instanceof Error ? error.message : "画像发布失败", true);
    } finally {
      setBusy(null);
    }
  }

  const appendExposureEvents = useCallback(
    (items: ContentItem[], requestId: string, startPosition = 0) => {
      const now = new Date().toISOString();
      setEvents((current) => {
        const next = items.map((item, index) => ({
          eventId: createEventId(),
          eventType: "EXPOSURE" as const,
          requestId,
          contentId: item.contentId,
          position: startPosition + index + 1,
          eventTime: now,
        }));
        return [...next, ...current].slice(0, 200);
      });
    },
    [],
  );

  const addInteraction = useCallback(
    (
      eventType: InteractionEvent["eventType"],
      item: ContentItem,
      position: number,
      requestId: string,
    ) => {
      const nextEvent: InteractionEvent = {
        eventId: createEventId(),
        eventType,
        requestId,
        contentId: item.contentId,
        position,
        eventTime: new Date().toISOString(),
      };
      setEvents((current) => [nextEvent, ...current].slice(0, 200));
      showToast(`${eventType} 已进入反馈队列`);
    },
    [showToast],
  );

  async function runSearch(targetPage = 0, targetQuery = query) {
    const cleanQuery = targetQuery.trim();
    if (!cleanQuery) {
      showToast("请输入搜索词", true);
      return;
    }
    setBusy("search");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ q: cleanQuery, page: String(targetPage), size: "9" });
      const data = await api<SearchResponse>(`/api/bridge/online/v1/search?${params}`);
      setQuery(cleanQuery);
      setSearchPage(targetPage);
      setSearchData(data);
      setHealth((current) => ({ ...current, online: "online" }));
      appendExposureEvents(data.hits, `search_${createEventId()}`);
      if (!data.hits.length) showToast("没有匹配结果，可以换一个更短的关键词");
    } catch (error) {
      const message = error instanceof Error ? error.message : "搜索失败";
      setDiscoverError(message);
      setHealth((current) => ({ ...current, online: "offline" }));
      showToast(message, true);
    } finally {
      setBusy(null);
    }
  }

  async function runFeed(cursor?: string | null, append = false) {
    setBusy("feed");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ page_size: "9" });
      if (interests.trim()) params.set("interests", interests.trim());
      if (feedSeed.trim()) params.set("seed_content_id", feedSeed.trim());
      if (cursor) params.set("cursor", cursor);
      const data = await api<FeedResponse>(`/api/bridge/online/v1/feed?${params}`, {
        headers: { "X-User-Id": userId || "anonymous" },
      });
      const startPosition = append ? feedItems.length : 0;
      setFeedData(data);
      setFeedItems((current) => (append ? [...current, ...data.items] : data.items));
      setHealth((current) => ({ ...current, online: "online" }));
      appendExposureEvents(data.items, data.requestId, startPosition);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Feed 生成失败";
      setDiscoverError(message);
      showToast(message, true);
    } finally {
      setBusy(null);
    }
  }

  async function runSimilar(contentSeed: string) {
    setDiscoverMode("feed");
    setFeedSeed(contentSeed);
    setBusy("feed");
    setDiscoverError("");
    try {
      const params = new URLSearchParams({ page_size: "9" });
      if (interests.trim()) params.set("interests", interests.trim());
      const data = await api<FeedResponse>(
        `/api/bridge/online/v1/contents/${encodeURIComponent(contentSeed)}/similar?${params}`,
        { headers: { "X-User-Id": userId || "anonymous" } },
      );
      setFeedData(data);
      setFeedItems(data.items);
      appendExposureEvents(data.items, data.requestId);
      showToast("已切换为相似内容召回");
    } catch (error) {
      const message = error instanceof Error ? error.message : "相似内容召回失败";
      setDiscoverError(message);
      showToast(message, true);
    } finally {
      setBusy(null);
    }
  }

  async function syncInteractions() {
    if (!events.length) {
      showToast("反馈队列还是空的");
      return;
    }
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
      setSyncMessage(`后端暂未接通：${message}。事件仍安全保留在本地。`);
      showToast("Interaction API 尚未可用，队列已保留", true);
    } finally {
      setBusy(null);
    }
  }

  const displayedItems = discoverMode === "search" ? searchData?.hits ?? [] : feedItems;
  const activeRequestId =
    discoverMode === "feed" ? feedData?.requestId ?? "feed_pending" : "search_frontend";
  const currentStage = content ? statusIndex[content.status] : 0;
  const eventCounts = useMemo(() => {
    const exposures = events.filter((event) => event.eventType === "EXPOSURE").length;
    return { exposures, actions: events.length - exposures };
  }, [events]);

  return (
    <div className="app-shell">
      {busy && <div className="loading-bar" aria-label="处理中" />}
      <aside className="sidebar">
        <div className="brand-lockup">
          <div className="brand-mark" aria-hidden="true">S</div>
          <div>
            <div className="brand-name">SeekFlux</div>
            <div className="brand-subtitle">Discovery infrastructure</div>
          </div>
        </div>

        <nav className="side-nav" aria-label="工作台导航">
          {navItems.map((item) => (
            <button
              key={item.key}
              className={`side-nav-button ${workspace === item.key ? "active" : ""}`}
              onClick={() => navigate(item.key)}
            >
              <span className="nav-number">{item.number}</span>
              <span className="nav-copy">
                <strong>{item.title}</strong>
                <small>{item.subtitle}</small>
              </span>
              {item.key === "feedback" && events.length > 0 && (
                <span className="nav-count">{Math.min(events.length, 99)}</span>
              )}
            </button>
          ))}
        </nav>

        <div className="side-status">
          <div className="side-status-title"><span className="pulse-dot" /> MVP 闭环进度</div>
          <div className="side-progress"><span /></div>
          <p>内容与发现链路已接通，反馈链路采用可回传的本地事件队列。</p>
        </div>
        <div className="side-version">ARCH BASELINE / V2.0</div>
      </aside>

      <div className="mobile-header">
        <div className="brand-lockup">
          <div className="brand-mark" aria-hidden="true">S</div>
          <div className="brand-name">SeekFlux</div>
        </div>
        <div className="mobile-tabs">
          {navItems.map((item) => (
            <button
              key={item.key}
              className={workspace === item.key ? "active" : ""}
              onClick={() => navigate(item.key)}
            >
              {item.title.replace("引擎", "").replace("中枢", "")}
            </button>
          ))}
        </div>
      </div>

      <main className="main-stage">
        <header className="topbar">
          <div className="topbar-path">SeekFlux / <strong>{navItems.find((item) => item.key === workspace)?.title}</strong></div>
          <div className="service-cluster" aria-label="后端服务状态">
            <ServicePill name="Content API" state={health.content} />
            <ServicePill name="Online API" state={health.online} />
          </div>
        </header>

        {workspace === "content" && (
          <ContentWorkspace
            creatorId={creatorId}
            setCreatorId={setCreatorId}
            title={title}
            setTitle={setTitle}
            description={description}
            setDescription={setDescription}
            sourceTags={sourceTags}
            setSourceTags={setSourceTags}
            mediaUri={mediaUri}
            setMediaUri={setMediaUri}
            submitContent={submitContent}
            contentId={contentId}
            setContentId={setContentId}
            content={content}
            contentMessage={contentMessage}
            currentStage={currentStage}
            loadContent={loadContent}
            pollContent={pollContent}
            profileVersion={profileVersion}
            setProfileVersion={setProfileVersion}
            profileSummary={profileSummary}
            setProfileSummary={setProfileSummary}
            profileTags={profileTags}
            setProfileTags={setProfileTags}
            transcript={transcript}
            setTranscript={setTranscript}
            publishProfile={publishProfile}
            busy={busy}
            goDiscover={() => navigate("discover")}
          />
        )}

        {workspace === "discover" && (
          <DiscoverWorkspace
            mode={discoverMode}
            setMode={setDiscoverMode}
            userId={userId}
            setUserId={setUserId}
            interests={interests}
            setInterests={setInterests}
            query={query}
            setQuery={setQuery}
            runSearch={runSearch}
            searchPage={searchPage}
            searchData={searchData}
            feedSeed={feedSeed}
            setFeedSeed={setFeedSeed}
            runFeed={runFeed}
            runSimilar={runSimilar}
            feedData={feedData}
            items={displayedItems}
            error={discoverError}
            busy={busy}
            requestId={activeRequestId}
            addInteraction={addInteraction}
          />
        )}

        {workspace === "feedback" && (
          <FeedbackWorkspace
            events={events}
            eventCounts={eventCounts}
            syncMessage={syncMessage}
            syncInteractions={syncInteractions}
            clearEvents={() => {
              setEvents([]);
              setSyncMessage("本地反馈队列已清空。");
            }}
            busy={busy}
          />
        )}
      </main>

      {toast && <div className={`toast ${toast.error ? "error" : ""}`} role="status">{toast.text}</div>}
    </div>
  );
}

function ServicePill({ name, state }: { name: string; state: HealthState }) {
  const label = state === "checking" ? "检测中" : state === "online" ? "已连接" : "未连接";
  return <div className={`service-pill ${state}`}><i />{name} · {label}</div>;
}

type ContentWorkspaceProps = {
  creatorId: string;
  setCreatorId: (value: string) => void;
  title: string;
  setTitle: (value: string) => void;
  description: string;
  setDescription: (value: string) => void;
  sourceTags: string;
  setSourceTags: (value: string) => void;
  mediaUri: string;
  setMediaUri: (value: string) => void;
  submitContent: (event: FormEvent) => void;
  contentId: string;
  setContentId: (value: string) => void;
  content: ContentResponse | null;
  contentMessage: string;
  currentStage: number;
  loadContent: () => Promise<ContentResponse | null>;
  pollContent: () => Promise<void>;
  profileVersion: number;
  setProfileVersion: (value: number) => void;
  profileSummary: string;
  setProfileSummary: (value: string) => void;
  profileTags: string;
  setProfileTags: (value: string) => void;
  transcript: string;
  setTranscript: (value: string) => void;
  publishProfile: (event: FormEvent) => void;
  busy: string | null;
  goDiscover: () => void;
};

function ContentWorkspace(props: ContentWorkspaceProps) {
  const pipeline = [
    ["内容登记", "PostgreSQL + Outbox"],
    ["任务投递", "Kafka · 异步处理"],
    ["画像生成", "基础摘要与标签"],
    ["质量门禁", "版本与状态校验"],
    ["索引发布", "Elasticsearch"],
  ];
  return (
    <>
      <section className="section-hero">
        <div>
          <div className="eyebrow">01 / Content intelligence</div>
          <h1>先让系统<br />知道内容<em>是什么。</em></h1>
        </div>
        <div className="hero-aside">
          <p>登记短视频元数据，观察它经过 Outbox、Kafka 与 Worker 生成画像并进入索引。这里对应当前已经跑通的第一条纵向切片。</p>
          <div className="micro-chips">
            <span className="micro-chip">POST /v1/contents</span>
            <span className="micro-chip">async profile</span>
            <span className="micro-chip">idempotent publish</span>
          </div>
        </div>
      </section>

      <section className="workspace-grid">
        <article className="panel">
          <div className="panel-header">
            <div><div className="panel-kicker">New content</div><h2>登记一条短视频</h2></div>
            <div className="panel-note">真实调用 Content API<br />提交后异步处理</div>
          </div>
          <form className="panel-body" onSubmit={props.submitContent}>
            <div className="field-grid">
              <label className="wide">
                <span className="field-label">内容标题 <span>必填</span></span>
                <input className="input" value={props.title} onChange={(event) => props.setTitle(event.target.value)} required maxLength={200} />
              </label>
              <label className="wide">
                <span className="field-label">内容描述 <span>用于基础画像</span></span>
                <textarea className="textarea" value={props.description} onChange={(event) => props.setDescription(event.target.value)} maxLength={4000} />
              </label>
              <label>
                <span className="field-label">创建者</span>
                <input className="input" value={props.creatorId} onChange={(event) => props.setCreatorId(event.target.value)} required maxLength={128} />
              </label>
              <label>
                <span className="field-label">来源标签 <span>逗号分隔</span></span>
                <input className="input" value={props.sourceTags} onChange={(event) => props.setSourceTags(event.target.value)} />
              </label>
              <label className="wide">
                <span className="field-label">媒体地址 <span>S3 / HTTPS</span></span>
                <input className="input" value={props.mediaUri} onChange={(event) => props.setMediaUri(event.target.value)} required />
              </label>
            </div>
            <div className="form-actions">
              <button className="button accent" disabled={props.busy === "content-submit"}>登记并开始处理 <span aria-hidden="true">→</span></button>
              <button
                type="button"
                className="button secondary"
                onClick={() => {
                  props.setTitle(sampleContent.title);
                  props.setDescription(sampleContent.description);
                  props.setCreatorId(sampleContent.creatorId);
                  props.setSourceTags(sampleContent.tags);
                  props.setMediaUri(sampleContent.mediaUri);
                }}
              >载入示例</button>
            </div>
          </form>
        </article>

        <article className="panel pipeline-panel">
          <div className="panel-header">
            <div><div className="panel-kicker">Processing trace</div><h2>内容处理轨迹</h2></div>
            <div className="panel-note">SUBMITTED<br />→ PUBLISHED</div>
          </div>
          <div className="content-id-box">
            <label htmlFor="content-id">Content ID</label>
            <input id="content-id" className="content-id-input" value={props.contentId} onChange={(event) => props.setContentId(event.target.value)} placeholder="登记后自动填入，也可粘贴已有 ID" />
          </div>
          <div className="pipeline">
            {pipeline.map(([name, detail], index) => {
              const state = index < props.currentStage ? "done" : index === props.currentStage && props.content ? "current" : "";
              return (
                <div className={`pipeline-step ${state}`} key={name}>
                  <span className="pipeline-node">{state === "done" ? "✓" : index + 1}</span>
                  <span className="pipeline-copy"><strong>{name}</strong><small>{detail}</small></span>
                  <span className="pipeline-state">{state === "done" ? "done" : state === "current" ? "active" : "waiting"}</span>
                </div>
              );
            })}
          </div>
          <div className="pipeline-actions">
            <button className="button secondary" onClick={() => void props.loadContent()} disabled={!props.contentId || props.busy === "content-load"}>查询状态</button>
            <button className="button mint" onClick={() => void props.pollContent()} disabled={!props.contentId || props.busy === "content-poll"}>等待发布</button>
          </div>
          <div className={`result-banner ${props.content?.status === "PUBLISHED" ? "success" : props.contentMessage.includes("无法") || props.contentMessage.includes("失败") ? "error" : ""}`} style={{ margin: "0 24px 24px" }}>
            {props.contentMessage}
            {props.content?.status === "PUBLISHED" && (
              <button className="button compact mint" style={{ marginLeft: 10 }} onClick={props.goDiscover}>去搜索验证 →</button>
            )}
          </div>
        </article>

        <article className="panel profile-editor">
          <div className="panel-header">
            <div><div className="panel-kicker">Human in the loop</div><h2>人工校准画像</h2></div>
            <span className="status-tag">阶段一 · 已接通</span>
          </div>
          <div className="profile-shell">
            <div className="profile-intro">
              <h3 className="panel-title">模型能力接入前，<br />仍可验证版本化发布。</h3>
              <p>ASR、OCR 与视觉理解将在后续接入；当前可以手工补全摘要、标签与转写，走相同的画像完成和发布契约。</p>
              <span className="micro-chip">PUT /profile → POST /publish</span>
            </div>
            <form onSubmit={props.publishProfile}>
              <div className="field-grid">
                <label>
                  <span className="field-label">画像版本</span>
                  <input className="input" type="number" min={1} value={props.profileVersion} onChange={(event) => props.setProfileVersion(Number(event.target.value))} />
                </label>
                <label>
                  <span className="field-label">画像标签 <span>逗号分隔</span></span>
                  <input className="input" value={props.profileTags} onChange={(event) => props.setProfileTags(event.target.value)} />
                </label>
                <label className="wide">
                  <span className="field-label">画像摘要</span>
                  <textarea className="textarea" value={props.profileSummary} onChange={(event) => props.setProfileSummary(event.target.value)} required />
                </label>
                <label className="wide">
                  <span className="field-label">ASR 转写 <span>当前可选 / 后续自动生成</span></span>
                  <textarea className="textarea" value={props.transcript} onChange={(event) => props.setTranscript(event.target.value)} placeholder="粘贴语音转写文本，用于检索召回…" />
                </label>
              </div>
              <div className="form-actions">
                <button className="button" disabled={!props.contentId || props.busy === "profile-publish"}>保存并重新发布</button>
              </div>
            </form>
          </div>
        </article>
      </section>
    </>
  );
}

type DiscoverWorkspaceProps = {
  mode: DiscoverMode;
  setMode: (mode: DiscoverMode) => void;
  userId: string;
  setUserId: (value: string) => void;
  interests: string;
  setInterests: (value: string) => void;
  query: string;
  setQuery: (value: string) => void;
  runSearch: (page?: number, query?: string) => Promise<void>;
  searchPage: number;
  searchData: SearchResponse | null;
  feedSeed: string;
  setFeedSeed: (value: string) => void;
  runFeed: (cursor?: string | null, append?: boolean) => Promise<void>;
  runSimilar: (contentId: string) => Promise<void>;
  feedData: FeedResponse | null;
  items: ContentItem[];
  error: string;
  busy: string | null;
  requestId: string;
  addInteraction: (type: InteractionEvent["eventType"], item: ContentItem, position: number, requestId: string) => void;
};

function DiscoverWorkspace(props: DiscoverWorkspaceProps) {
  const resultMeta = props.mode === "search"
    ? props.searchData
      ? `“${props.searchData.query}” · ${props.searchData.total} 条 · ${props.searchData.tookMillis} ms`
      : "等待第一次检索"
    : props.feedData
      ? `${props.feedData.requestId} · ${props.items.length} 条`
      : "等待生成 Feed";

  return (
    <>
      <section className="section-hero">
        <div>
          <div className="eyebrow">02 / Search & recommendation</div>
          <h1>把相关内容，<br />送到<em>正确的人。</em></h1>
        </div>
        <div className="hero-aside">
          <p>关键词搜索与推荐共享同一份已发布画像。搜索强调 Query 相关性，Feed 组合热门、显式兴趣与相似内容三路召回。</p>
          <div className="micro-chips"><span className="micro-chip">BM25 baseline</span><span className="micro-chip">RRF fusion</span><span className="micro-chip">signed cursor</span></div>
        </div>
      </section>

      <div className="discover-toolbar">
        <div className="switcher" role="tablist" aria-label="发现模式">
          <button className={props.mode === "search" ? "active" : ""} onClick={() => props.setMode("search")}>主动搜索</button>
          <button className={props.mode === "feed" ? "active" : ""} onClick={() => props.setMode("feed")}>推荐 Feed</button>
        </div>
        <div className="identity-fields">
          <input className="identity-input" value={props.userId} onChange={(event) => props.setUserId(event.target.value)} aria-label="用户 ID" placeholder="用户 ID" />
          <span className="status-tag">{props.mode === "search" ? "阶段二 · 已接通" : "阶段三 · 推荐 Feed 已接通"}</span>
        </div>
      </div>

      <section className="search-block">
        {props.mode === "search" ? (
          <>
            <div className="search-block-header"><h2>搜索已发布的内容画像</h2><p>当前使用 Elasticsearch 关键词基线；语义向量、复杂 Query 理解与个性化排序保留在后续阶段。</p></div>
            <form className="search-form" onSubmit={(event) => { event.preventDefault(); void props.runSearch(0); }}>
              <input className="search-input" value={props.query} onChange={(event) => props.setQuery(event.target.value)} placeholder="试试：上海亲子博物馆 / 新手手冲咖啡" aria-label="搜索内容" />
              <button className="search-submit" disabled={props.busy === "search"}>开始检索 →</button>
            </form>
            <div className="query-suggestions">
              {['杭州 周末 露营', '新手 手冲 咖啡', '上海 亲子 博物馆', '川西 自驾 摄影'].map((suggestion) => (
                <button key={suggestion} onClick={() => { props.setQuery(suggestion); void props.runSearch(0, suggestion); }}>{suggestion}</button>
              ))}
            </div>
          </>
        ) : (
          <>
            <div className="search-block-header"><h2>生成可解释推荐 Feed</h2><p>显式兴趣解决新用户冷启动；内容 ID 作为最近行为种子，触发 Item-Item 相似召回。</p></div>
            <div className="feed-config">
              <label>
                <span className="field-label" style={{ color: "#c3c7c0" }}>显式兴趣 <span>逗号分隔</span></span>
                <input className="search-input" value={props.interests} onChange={(event) => props.setInterests(event.target.value)} placeholder="露营, 亲子" />
              </label>
              <label>
                <span className="field-label" style={{ color: "#c3c7c0" }}>最近内容 ID <span>可选</span></span>
                <input className="search-input" value={props.feedSeed} onChange={(event) => props.setFeedSeed(event.target.value)} placeholder="触发相似召回" />
              </label>
              <button className="button accent full wide" onClick={() => void props.runFeed()} disabled={props.busy === "feed"}>热门 + 兴趣 + 相似，多路召回 →</button>
            </div>
            <div className="feed-health"><i /> 单路召回超时会返回可观察的降级结果，不阻断整个 Feed</div>
          </>
        )}
      </section>

      {props.feedData?.degraded && props.mode === "feed" && (
        <div className="degraded-note">部分召回源已降级：{props.feedData.unavailableSources.join("、")}。页面仍展示已返回的候选。</div>
      )}

      <div className="results-header">
        <h3>{props.mode === "search" ? "搜索结果" : "为你推荐"}</h3>
        <div className="results-meta">{resultMeta}</div>
      </div>

      <div className="result-grid">
        {props.error && !props.items.length ? (
          <EmptyState symbol="!" title="发现服务暂时不可用" text={`${props.error}。请先启动 online-server 与 Elasticsearch，页面会继续通过真实接口工作。`} />
        ) : props.items.length ? (
          props.items.map((item, index) => (
            <MediaCard
              key={`${item.contentId}-${index}`}
              item={item}
              index={index}
              requestId={props.requestId}
              addInteraction={props.addInteraction}
              runSimilar={props.runSimilar}
            />
          ))
        ) : (
          <EmptyState
            symbol="⌕"
            title={props.mode === "search" ? "从一个问题开始" : "还没有生成 Feed"}
            text={props.mode === "search" ? "输入关键词或自然语言问题，验证内容登记 → 画像发布 → Elasticsearch 召回的完整链路。" : "填入兴趣标签生成冷启动 Feed；如果已有内容 ID，还可以观察相似召回。"}
          />
        )}
      </div>

      {props.mode === "search" && props.searchData && props.searchData.total > 0 && (
        <div className="pagination">
          <button className="button secondary compact" disabled={props.searchPage === 0 || props.busy === "search"} onClick={() => void props.runSearch(props.searchPage - 1)}>← 上一页</button>
          <span className="micro-chip">PAGE {props.searchPage + 1}</span>
          <button className="button secondary compact" disabled={(props.searchPage + 1) * props.searchData.size >= props.searchData.total || props.busy === "search"} onClick={() => void props.runSearch(props.searchPage + 1)}>下一页 →</button>
        </div>
      )}
      {props.mode === "feed" && props.feedData?.nextCursor && (
        <div className="pagination"><button className="button secondary" disabled={props.busy === "feed"} onClick={() => void props.runFeed(props.feedData?.nextCursor, true)}>使用 Cursor 加载更多 ↓</button></div>
      )}
    </>
  );
}

function MediaCard({
  item,
  index,
  requestId,
  addInteraction,
  runSimilar,
}: {
  item: ContentItem;
  index: number;
  requestId: string;
  addInteraction: DiscoverWorkspaceProps["addInteraction"];
  runSimilar: (contentId: string) => Promise<void>;
}) {
  const titleWord = item.tags?.[0] || item.title.slice(0, 5);
  return (
    <article className="media-card">
      <div className={`media-cover cover-${index % 6}`}>
        {item.sources?.length ? <div className="source-row">{item.sources.slice(0, 2).map((source) => <span className="source-mini" key={source}>{source}</span>)}</div> : null}
        <div className="score-badge">SCORE {Number(item.score).toFixed(3)}</div>
        <div className="cover-word">{titleWord}</div>
      </div>
      <div className="media-card-body">
        <h4>{item.title}</h4>
        <p className="card-summary">{item.summary || item.description}</p>
        <div className="tag-row">{item.tags.slice(0, 4).map((tag) => <span className="tag" key={tag}>#{tag}</span>)}</div>
        {item.reason && <div className="reason-line">{item.reason}</div>}
        <div className="card-actions">
          <button className="action-chip" onClick={() => { addInteraction("PLAY_START", item, index + 1, requestId); window.open(item.mediaUri, "_blank", "noopener,noreferrer"); }}>播放 ↗</button>
          <button className="action-chip like" onClick={() => addInteraction("LIKE", item, index + 1, requestId)}>喜欢 +</button>
          <button className="action-chip" onClick={() => void runSimilar(item.contentId)}>看相似</button>
          <button className="action-chip" aria-label="不感兴趣" onClick={() => addInteraction("NOT_INTERESTED", item, index + 1, requestId)}>隐藏</button>
        </div>
      </div>
    </article>
  );
}

function EmptyState({ symbol, title, text }: { symbol: string; title: string; text: string }) {
  return <div className="empty-state"><div className="empty-symbol">{symbol}</div><strong>{title}</strong><p>{text}</p></div>;
}

function FeedbackWorkspace({
  events,
  eventCounts,
  syncMessage,
  syncInteractions,
  clearEvents,
  busy,
}: {
  events: InteractionEvent[];
  eventCounts: { exposures: number; actions: number };
  syncMessage: string;
  syncInteractions: () => Promise<void>;
  clearEvents: () => void;
  busy: string | null;
}) {
  return (
    <>
      <section className="section-hero">
        <div>
          <div className="eyebrow">03 / Realtime feedback</div>
          <h1>每一次选择，<br />都成为<em>下一次信号。</em></h1>
        </div>
        <div className="hero-aside">
          <p>页面已经能生成与曝光关联的行为事件。Interaction、实时特征和短期兴趣后端尚未完成时，事件会保存在本地而不会丢失或伪报成功。</p>
          <div className="micro-chips"><span className="micro-chip">event_id</span><span className="micro-chip">request_id</span><span className="micro-chip">position aware</span></div>
        </div>
      </section>

      <section className="feedback-overview">
        <article className="panel loop-card">
          <div className="eyebrow" style={{ color: "var(--mint)" }}>Minimum viable loop</div>
          <h2>前端闭环已经成形，<br /><em>实时回流等待接棒。</em></h2>
          <p>从内容发布到曝光和互动，所有关键上下文已经保留。后续只需让 Interaction API 接收队列，并由 Kafka / Flink 更新短期兴趣。</p>
          <div className="loop-rail">
            {[['内容', 'done'], ['索引', 'done'], ['召回', 'done'], ['行为', 'current'], ['特征', 'planned']].map(([name, state]) => (
              <div className={`loop-node ${state}`} key={name}><span className="loop-dot" /><strong>{name}</strong><small>{state}</small></div>
            ))}
          </div>
        </article>

        <article className="panel queue-card">
          <div className="panel-header">
            <div><div className="panel-kicker">Local event buffer</div><h2>行为事件队列</h2></div>
            <span className="status-tag planned">接口占位</span>
          </div>
          <div className="panel-body">
            <div className="queue-summary">
              <div className="queue-stat"><strong>{eventCounts.exposures}</strong><span>曝光事件</span></div>
              <div className="queue-stat"><strong>{eventCounts.actions}</strong><span>主动行为</span></div>
            </div>
            <div className="queue-list">
              {events.length ? events.slice(0, 30).map((event) => (
                <div className="queue-item" key={event.eventId}>
                  <span className="event-type">{event.eventType}</span>
                  <span className="event-content">{shortId(event.contentId)}</span>
                  <span className="event-time">{formatEventTime(event.eventTime)}</span>
                </div>
              )) : <EmptyState symbol="0" title="队列为空" text="去发现引擎检索或生成 Feed，曝光与互动会自动出现在这里。" />}
            </div>
            <div className="result-banner">{syncMessage}</div>
            <div className="form-actions">
              <button className="button accent" disabled={!events.length || busy === "sync"} onClick={() => void syncInteractions()}>尝试批量回传 →</button>
              <button className="button ghost" disabled={!events.length} onClick={clearEvents}>清空本地队列</button>
            </div>
          </div>
        </article>

        <div className="roadmap-grid">
          <article className="panel roadmap-card"><span className="roadmap-number">NEXT / 01</span><h3>Interaction API</h3><p>幂等接收曝光、播放、点赞和负反馈，并校验 requestId、位置与事件时间。</p><div className="roadmap-foot"><span>POST /interactions:batch</span><span>待实现</span></div></article>
          <article className="panel roadmap-card"><span className="roadmap-number">NEXT / 02</span><h3>实时兴趣</h3><p>由 Kafka + Flink 去重和窗口聚合，将会话与短期兴趣写入在线特征存储。</p><div className="roadmap-foot"><span>Feature + Redis</span><span>待实现</span></div></article>
          <article className="panel roadmap-card"><span className="roadmap-number">NEXT / 03</span><h3>可验证再排序</h3><p>同一用户再次生成 Feed 时，展示兴趣变化、召回来源与最终位置的 Ranking Trace。</p><div className="roadmap-foot"><span>Experiment + Trace</span><span>规划中</span></div></article>
        </div>
      </section>
    </>
  );
}
