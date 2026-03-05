# Chat-Bot — Customer Engagement Platform

A multi-platform Clojure chatbot for e-commerce customer engagement via **Telegram** and **MAX** messengers.

## Features

- **Promo campaigns** — issue promo codes on channel subscription, track history, FOMO hints
- **Broadcast notifications** — send campaigns to all users with per-user rate limiting
- **Review collection** — multi-step submission with photo support, admin moderation queue
- **Support chat** — built-in FSM chat mode or Chatwoot CRM integration
- **Admin panel** — inline UI for managing campaigns, moderating reviews, viewing stats
- **Multi-platform** — unified hexagonal architecture for Telegram and MAX adapters

## Tech Stack

- **Language**: Clojure (JVM 21)
- **Database**: PostgreSQL 15 + HoneySQL + HikariCP
- **HTTP**: http-kit + Compojure + Ring
- **Config**: environment variables via `environ`
- **Reliability**: circuit breaker + rate limiter on all external API calls
- **Logging**: Logback with rolling file output

## Project Structure

```
src/chatbot/
├── adapters/
│   ├── telegram/        # Telegram Bot API adapter
│   ├── max/             # MAX messenger adapter
│   ├── persistence/     # PostgreSQL repos + migrations
│   └── chatwoot/        # Chatwoot CRM adapter + webhook
├── domain/
│   ├── services/        # promo, broadcast, support
│   ├── entities.clj     # FSM states
│   ├── messages.clj     # all text + keyboard definitions
│   └── router.clj       # message routing
├── ports/               # protocols: repository, messaging, crm
├── web/                 # HTTP server + webhook routes
└── core.clj             # entrypoint, wiring
resources/
├── migrations/          # numbered SQL migration files
└── logback.xml
```

## Local Development

**Prerequisites**: Java 21, [Clojure CLI](https://clojure.org/guides/install_clojure), Docker

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Copy and fill in env (fill APP_DATABASE_URL for local dev)
cp .env.example .env

# 3. Run the bot
clojure -M:run
```

Migrations run automatically on startup.

## Configuration

All configuration is via environment variables. See [`.env.example`](.env.example) for the full reference.

Key variables:

| Variable | Description |
|---|---|
| `TG_BOT_TOKEN` | Telegram bot token from @BotFather |
| `MAX_BOT_TOKEN` | MAX messenger bot token |
| `MAX_ENABLED` | Enable MAX adapter (default: `false`) |
| `APP_DATABASE_URL` | PostgreSQL JDBC URL |
| `CHATWOOT_ENABLED` | Enable Chatwoot CRM integration (default: `false`) |
| `CHATWOOT_WEBHOOK_SECRET` | Required when Chatwoot is enabled |
| `TG_ADMIN_ID` | Admin Telegram user ID for moderation |

## Production Deployment

The project ships with a Docker-based deployment pipeline.

```bash
# Build uberjar
clojure -T:build uber

# Build image locally
docker build -t chat-bot .

# Run with Docker Compose
cp .env.example .env
# fill in POSTGRES_*, DOCKER_IMAGE, bot tokens
docker compose -f docker-compose.prod.yml up -d
```

### CI/CD

On every push to `main`, GitHub Actions:
1. Builds the uberjar
2. Publishes Docker image to `ghcr.io/<org>/chat-bot` (tags: `latest` + `sha-<commit>`)
3. Deploys to production server via SSH

**Required GitHub Actions secrets:**

| Secret | Description |
|---|---|
| `SERVER_HOST` | Production server IP or hostname |
| `SERVER_USER` | SSH username |
| `SSH_PRIVATE_KEY` | Private key for SSH access |

Server must have Docker + Docker Compose installed with `/opt/chatbot/.env` populated.

## License

MIT
