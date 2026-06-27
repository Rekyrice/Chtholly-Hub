# Chtholly Hub — Agent Guide

> This file is the single source of truth for any coding agent working on this project.
> Read this FIRST before making any changes.

## Project Overview

Chtholly Hub is an anime blog-to-community platform built as a portfolio project for autumn recruitment (秋招 2026). It starts as a personal anime blog and evolves into a community with AI-powered content intelligence.

- **GitHub**: https://github.com/Rekyrice/Chtholly-Hub
- **Stack**: Java 21 + Spring Boot 3.2.4 (backend) / Next.js 16 + Tailwind CSS 4 (frontend)
- **Architecture**: Monorepo — `apps/server` (Spring Boot) + `apps/web` (Next.js)

## Quick Start

```bash
# Backend
cd apps/server
cp .env.example .env   # configure environment variables
mvn spring-boot:run    # starts on http://localhost:8080

# Frontend
cd apps/web
npm install
npm run dev            # starts on http://localhost:3000, proxies /api/* to :8080

# Infrastructure (Docker)
docker compose -f docker-compose.prod.yml up -d
```

## Module Map

### Backend (`apps/server/src/main/java/com/chtholly/`)

| Package | Purpose | Key Files |
|---------|---------|-----------|
| `auth` | RS256 JWT dual-token auth, SMS verification, XSRF | `SecurityConfig`, `AuthService`, `JwtService` |
| `post` | Article CRUD, multi-level cache (Caffeine L1 + Redis L2), SingleFlight, hot-key detection | `PostServiceImpl`, `PostFeedServiceImpl`, `HotKeyDetector` |
| `counter` | Distributed like/favorite/view counters — Redis Bitmap + SDS + Kafka async | `CounterServiceImpl`, `CounterAggregationConsumer` |
| `relation` | Follow/unfollow — dual-table model, Outbox + Canal CDC | `RelationServiceImpl`, `CanalOutboxConsumer` |
| `comment` | Two-level nested comments with notification events | `CommentServiceImpl`, `CommentController` |
| `notification` | Event-driven notifications (Spring ApplicationEvent) | `NotificationServiceImpl`, `NotificationEventListener` |
| `tag` | Tag CRUD with post association and usage counting | `TagServiceImpl`, `TagController` |
| `search` | Elasticsearch full-text search with IK tokenizer, cursor pagination | `SearchServiceImpl`, `SearchIndexService`, `SearchIndexInitializer` |
| `bangumi` | Bangumi API client, local cache, anime data sync | `BangumiClient`, `BangumiServiceImpl`, `BangumiSyncJob` |
| `agent` | Custom ReAct Agent engine with WebSocket streaming | `ChthollyAgent`, `AgentTool`, `AgentWebSocketHandler` |
| `agent/tools` | Agent tools: BangumiSearch, ArticleRAG, FulltextSearch, BangumiCharacters, BangumiPersonWorks | `*Tool.java` |
| `agent/memory` | In-memory conversation memory (planned: Redis migration) | `AgentConversationMemory`, `AgentMemoryStore` |
| `storage` | OSS presigned upload, avatar management | `OssStorageService`, `StorageController` |
| `profile` | User profile editing, avatar upload | `ProfileServiceImpl`, `ProfileController` |
| `user` | Public user info, user profile page | `UserPublicServiceImpl`, `UserController` |
| `common` | Global exception handler, snowflake ID generator, slug utils, pagination | `GlobalExceptionHandler`, `SnowflakeIdGenerator`, `SlugUtils` |
| `config` | Elasticsearch, Redis, Redisson, Kafka, ThreadPool configs | `*Config.java` |
| `llm` | Spring AI integration, RAG indexing, SSE streaming | `RagQueryService`, `PostRagIndexer` |

### Frontend (`apps/web/`)

| Directory | Purpose |
|-----------|---------|
| `app/(site)/` | Public pages: Home, Post detail, Archive, Tag, About, Search, User profile, Agent |
| `app/(site)/login/` | Login/register with phone + SMS code |
| `app/(site)/write/` | Markdown post editor with 5-step publish flow |
| `components/site/` | Shared UI: Navbar, Sidebar, PostCard, Footer, CommentSection, NotificationBell |
| `components/agent/` | Agent chat interface (AgentChat.tsx) |
| `lib/services/` | API client + service modules (auth, post, comment, search, tag, notification, storage, user) |
| `lib/types/` | TypeScript type definitions |
| `lib/auth/` | Client-side JWT token management |

### Database

- **Schema**: `apps/server/db/schema.sql` (base tables)
- **Migrations**: `apps/server/db/migration/V5__tags.sql` through `V8__bangumi_tables.sql`
- **Seed data**: `apps/server/db/seed/phase_a_seed.sql`
- **ORM**: MyBatis XML mappers at `apps/server/src/main/resources/mapper/`

## Coding Conventions

### Backend (Java)

1. **Package structure**: `{module}/api/` (controllers + DTOs), `{module}/service/` (interfaces), `{module}/service/impl/` (implementations), `{module}/mapper/` (MyBatis), `{module}/model/` (entities/rows)
2. **Docstrings & Comments (layered language strategy)**:
   - **Class-level Javadoc → English**: Every class gets an English Javadoc describing its role and position in the system. This is the "public face" of the code.
   - **Method-level Javadoc → English**: Public method signatures (`@param`, `@return`, `@throws`) in English.
   - **Internal WHY comments → Chinese**: Inside method bodies, complex logic comments use Chinese. These explain WHY a design choice was made, not WHAT the code does. During interviews, these serve as ready-made explanations.
   - **Inline trivial comments → Either**: Simple inline comments can use whichever language is more natural.
   - Example:
     ```java
     /**
      * Multi-level cache feed service for public post listings.
      * (English — class-level, public face)
      */
     public class PostFeedServiceImpl {
         public FeedPage getPublicFeed(int page, int size) {
             // 用 SingleFlight 而不是直接查缓存，是因为缓存同时过期时
             // 几十个并发请求会同时打到 DB（缓存击穿）。
             // SingleFlight 保证同一 pageKey 只有一个请求真正查 DB，其余复用结果。
             // (Chinese — internal WHY comment)
         }
     }
     ```
3. **Error handling**: Use `BusinessException` with HTTP status codes. NEVER use `catch (Exception ignored) {}`. Every catch block must at minimum log the exception.
4. **Configuration**: All environment-specific values via `${VAR:default}` in `application.yml`. Feature flags for optional modules (`LLM_ENABLED`, `CANAL_ENABLED`).
5. **SQL**: All MyBatis queries use `#{param}` (parameterized). NEVER use `${param}` (SQL injection).
6. **IDs**: Use `SnowflakeIdGenerator` for all entity IDs. Never use auto-increment.

### Frontend (TypeScript/React)

1. **Server Components by default**: Pages under `app/(site)/` are server components with ISR (`revalidate = 60`). Only use `"use client"` when interactivity is required.
2. **API calls**: Server components call `postService.*` directly. Client components use `apiClient` from `lib/services/apiClient.ts`.
3. **Styling**: Tailwind CSS utility classes + CSS custom properties in `globals.css`. Theme colors via `--blog-primary`, `--blog-secondary`.
4. **Types**: All API responses and request bodies have TypeScript types in `lib/types/`.

### Git

1. **Commit messages**: Conventional commits — `feat:`, `fix:`, `refactor:`, `chore:`, `docs:`, `test:`
2. **Commit language**: English
3. **Branch naming**: `feat/{description}`, `fix/{description}`

## Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| Multi-level cache (Caffeine + Redis) | L1 for hot keys (ns latency), L2 for warm data (ms latency), DB as source of truth |
| SingleFlight pattern | Prevents cache stampede — concurrent requests for same page hit DB only once |
| Outbox + Canal CDC | Decouples write path from search index / relation sync without 2PC |
| Spring ApplicationEvent (not Kafka) for notifications | Deliberate simplification — notifications are not critical path, sync is acceptable for MVP |
| Custom ReAct Agent (not Spring AI Agent / AstrBot) | ~50 lines of core loop code, full control, no framework overhead |
| MySQL adjacency tables for knowledge graph (not Neo4j) | Small dataset (thousands of nodes), BFS in Java is clearer than Cypher for this use case |
| Redis Bitmap for like/fav idempotency | O(1) check, O(1) set, bitcount for totals — extremely memory efficient |

## Known Technical Debt

> These are documented improvement areas. Check the prompt files for detailed execution plans.

- **Agent**: `chtholly-hub-improvement-prompts.md` (20 items: P0 correctness, P1 maintainability, P2 maturity)
- **Backend**: `chtholly-hub-backend-prompts.md` (12 items: security, reliability, system depth)
- **Frontend**: `chtholly-hub-frontend-prompts.md` (5 items: color scheme, hero banner, animations, Live2D prep)

## Deployment

- **Production**: Docker Compose on single ECS instance
- **Compose file**: `docker-compose.prod.yml` (Spring Boot + Next.js standalone + MySQL + Redis + Nginx)
- **Nginx**: `docker/nginx/default.conf` — reverse proxy `/api` to Spring Boot, serve Next.js
- **OSS**: Alibaba Cloud OSS for Markdown content and avatar storage
- **Degradation paths**: Drop Kafka → Spring Event, Drop ES → MySQL LIKE (documented in planning doc)

## Environment Variables

See `.env.example` for development and `.env.prod.example` for production. Key variables:

| Variable | Purpose | Default |
|----------|---------|---------|
| `MYSQL_HOST` | Database host | `localhost` |
| `REDIS_HOST` | Redis host | `localhost` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker | `localhost:9092` |
| `ES_URIS` | Elasticsearch cluster | `http://localhost:9200` |
| `DEEPSEEK_API_KEY` | DeepSeek LLM API key | (none) |
| `DASHSCOPE_API_KEY` | DashScope embedding API key | (none) |
| `LLM_ENABLED` | Enable LLM/Agent features | `false` |
| `OSS_*` | Alibaba Cloud OSS credentials | (none) |
| `BANGUMI_TOKEN` | Bangumi API personal token | (none) |
