# DeskFlow

[![Build and test](https://github.com/Kojizz/deskflow/actions/workflows/ci.yml/badge.svg)](https://github.com/Kojizz/deskflow/actions/workflows/ci.yml)

A visual workflow automation platform built with **Java 21, Spring Boot, PostgreSQL, RabbitMQ, React and TypeScript**. Connect triggers and actions, run workflows, and inspect every execution.

![DeskFlow overview](docs/screenshots/overview.png)

## Features

- Visual workflow editor with 20 step types, six starter templates and import/export.
- Manual, webhook, form and scheduled triggers, including cron expressions and time zones.
- Conditions, filters, switch paths, joins, JSON mapping, formatters and collection operations.
- Durable delays, human approvals, reusable subworkflows and bounded loops.
- HTTP requests, SMTP email, workspace data tables and compatible AI text actions.
- Execution history, retries, cancellation, replay, resume and immutable workflow versions.
- **Impact Lab:** preview planned actions, compare versions using the same input and enforce a shared live action budget.
- Private workspaces, hashed sessions, BCrypt passwords and encrypted bearer credentials.
- Responsive dark interface with top navigation and reduced-motion support.

Slack and GitHub integrations are editable API recipes that require your own credentials. AI actions require a provider endpoint and model. No paid service is connected automatically.

## Stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring JDBC, Spring Security |
| Persistence | PostgreSQL 17, JSONB, Flyway |
| Execution | RabbitMQ, transactional outbox, database leases and recovery polling |
| Frontend | React 19, TypeScript, Vite, React Flow |
| Development | Docker Compose, Mailpit, Maven, Vitest, GitHub Actions |

## Run with Docker

Install Docker Desktop and enable its Linux engine. From the repository root in PowerShell:

```powershell
.\scripts\setup.ps1
docker compose up --build -d
```

- App: http://localhost:8088
- Local email inbox: http://localhost:8025
- API documentation: http://localhost:8088/swagger-ui/index.html

Create an account in the app, then create a workflow from a starter template. Setup generates database and broker passwords plus an encryption key in ignored `.env`. Retain this key to keep saved credentials readable. PostgreSQL and RabbitMQ data persist in Docker volumes.

Stop with `docker compose down`. Adding `--volumes` erases the stored database and queue.

## Native Windows development

Requirements: Java 21+, Node 22+ and PostgreSQL 17. The launcher downloads official Maven and Mailpit binaries when needed.

```powershell
.\scripts\start-local.ps1
# App: http://127.0.0.1:5173
# API: http://127.0.0.1:18080

# Stop launcher-managed services; retain database files:
.\scripts\stop-local.ps1
```

Native mode uses an isolated PostgreSQL cluster on `127.0.0.1:55432` and database polling instead of RabbitMQ. Existing project services are reused; unrelated processes are never replaced. Use `-PostgresBin` to select another PostgreSQL installation.

If another project uses frontend port 5173, run `./scripts/start-local.ps1 -FrontendPort 5174`.
The launcher remembers this port for later starts, including IntelliJ preparation,
and prints the current app address. It never stops the other project.

For IntelliJ IDEA, import `backend/pom.xml`. After initial setup, use **Application** or **DeskFlow Local**. Both select the `local` Spring profile and prepare dependencies before startup. The preparation task releases a recorded launcher-owned API so IntelliJ can own port 18080.

To prepare dependencies manually without starting a second backend:

```powershell
.\scripts\prepare-intellij.ps1 -SkipBuild
# Then use IntelliJ Run.
```

For frontend development after dependencies are ready:

```powershell
cd frontend
npm ci
npm run dev
```

`npm run build` creates the production frontend bundle. `npm run dev` starts the development server.

## Google and GitHub sign-in

Password sign-in remains available. Social sign-in is enabled per provider only
when both its client ID and secret are configured in the ignored root `.env`:

```dotenv
APP_PUBLIC_URL=http://127.0.0.1:5174
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
```

Register a Google OAuth **Web application** and a GitHub **OAuth App** named
DeskFlow. The exact callbacks for the current local frontend are:

- Google: `http://127.0.0.1:5174/api/auth/oauth2/callback/google`
- GitHub: `http://127.0.0.1:5174/api/auth/oauth2/callback/github`

Use the same hostname/port when opening the app. For Docker, set `APP_PUBLIC_URL`
and registered callbacks to its frontend origin (normally `http://localhost:8088`).
Restart the backend after changing credentials. Provider readiness is available
at `/api/auth/oauth2/providers`; no credentials are returned by this API.

Google requests OpenID profile/email; GitHub requests `read:user` and `user:email`,
without repository access. **Continue with Google** signs in without a DeskFlow
password. A verified Gmail or Google Workspace identity can open the existing
workspace for that email directly. An external email used with Google may need a
one-time email confirmation; it never asks for a DeskFlow password. Unverified
credentials from a possible pre-signup are retired when mailbox ownership is first
proven; workflows and workspace data are preserved. A valid existing session keeps
the owner's password when connecting Google.

GitHub email collisions require confirmation with the existing password or an
already-connected provider. You can also connect providers from **Account →
Connected accounts**. Confirmation expires after ten minutes. Identities use Google
`sub` or GitHub user ID; provider access/refresh tokens are not retained after sign-in.

Google Cloud project: **DeskFlow**, ID `supple-alpha-512517-f2`. The Google
client and GitHub OAuth App are registered for the local origin above. Google is
in testing mode with the developer account added as a test user. Both provider
authorization screens have been verified, and Google's live callback has been
verified through existing-account confirmation. Complete consent on your first
sign-in. Passwordless Google sign-in and account recovery are covered by integration tests.

## Forgot password

Select **Forgot password?** under the password field on the sign-in form. Enter
your account email, open the email link, and choose a new password. Links expire
after 30 minutes, work once, and are stored only as hashes. A reset signs out old
sessions. Google sign-in continues to work; social-only accounts can also use this
flow to set a password. Responses do not disclose whether an email is registered.

Local emails appear in **Mailpit at http://127.0.0.1:8025**, rather than a real inbox.
For deployment, configure `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`,
`SMTP_AUTH`, `SMTP_STARTTLS` (or `SMTP_SSL` for implicit TLS), and `ACCOUNT_MAIL_FROM`
in the ignored `.env`. Use your public HTTPS frontend origin for `APP_PUBLIC_URL`.

## Try a workflow

1. Create the **Welcome message** starter.
2. Select a step to inspect its configuration.
3. Choose **Test workflow** and submit the sample input.
4. Open **Executions** to inspect each step and its output.
5. Use **Impact Lab** to preview a change before running it.

![Workflow editor](docs/screenshots/workflow-editor.png)

See [the feature guide](FEATURE_GUIDE.md) for approvals, forms, tables, nested workflows and recovery. [Architecture notes](ARCHITECTURE.md) explain transaction boundaries and execution guarantees.

## Tests

```powershell
cd backend
mvn verify

cd ../frontend
npm ci
npm test
npm run build

cd ..
node scripts/smoke.mjs http://127.0.0.1:5173
node scripts/smoke-core.mjs http://127.0.0.1:5173
```

Integration tests use a disposable PostgreSQL 17 container through Testcontainers. A separate disposable database can also be supplied using `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`. Never use a database containing real user data for tests. Live smoke scripts create their own fixture workspaces.

## Project layout

```text
backend/       Spring Boot API, workflow engine, migrations and tests
frontend/      React workspace and visual editor
scripts/       Local startup, setup and API smoke checks
.run/          Shared IntelliJ run configurations
.github/       Build and test workflows
```

## Engineering boundaries

External effects are at-least-once: recovery or replay can repeat a request or email. HTTP actions supply a stable per-step idempotency key; the receiving service must honor it. Cancellation does not undo completed effects.

Impact Lab simulations use explicit fixtures for external reads. They dispatch no HTTP requests, email or table writes, and do not prove a remote provider's behavior. Live action budgets count attempts across retries and nested workflows rather than currency.

This portfolio release has private user workspaces and a bounded execution engine. Team sharing, arbitrary-code execution and enterprise SSO are outside its scope. Public deployment requires TLS, secure cookies, network egress isolation, quotas, backups and monitoring. Keep secrets in the credential store, not workflow input or configuration.
