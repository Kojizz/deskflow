# DeskFlow 0.2 feature guide

## Start here

Run `./scripts/start-local.ps1` and open http://127.0.0.1:5173. Docker users use `docker compose up --build -d` and http://localhost:8088. Existing accounts and workflows are preserved. Six starter templates include **Request approval**, **Contact form** and **Recovery path**.

## Build a workflow

Use **Add a step**, select its card to configure it, then connect the output dots. There must be exactly one trigger and every step must connect to it. A workflow has at most 60 steps and 180 edges. Cycles are rejected; use a bounded Loop step for repeated work.

| Purpose | Steps / behavior |
| --- | --- |
| Start | Manual, Webhook, Schedule, Form submission |
| Route | Condition: true/false; Filter: stop unmatched data; Switch: first matching named case or default |
| Shape data | Transform JSON, Formatter, Collection, Merge branches |
| Wait / review | Delay: 1–86400 seconds; Human approval: inbox with approved/rejected branches |
| Reuse / repeat | Subworkflow; Loop over up to 20 items, nesting at most 5 levels |
| Act | Public HTTP, email, table operations, compatible AI chat completion |
| Handle errors | Per-step 1–5 attempts, retry delay, stop/continue/error route; explicit Stop with error step |

Values such as `"{{input.total}}"` preserve their JSON type. `"Hi {{input.name}}"` becomes text. Use `{{input.orders.0.total}}` for array access and `{{steps.format.value}}` for a previous output. Conditions and switches use paths without braces. A Merge exposes active incoming outputs in `steps.mergeId.parents`. All incoming paths must finish before a join; skipped paths do not block it. Ready steps run sequentially within an execution, including around waits; child executions may progress independently.

Formatter operations: uppercase, lowercase, trim, number, round, dateIso, jsonParse, jsonStringify. Collection operations: count, pluck, unique, sort, limit, sum, filter; maximum 500 input items. Count returns `{count}`, sum returns `{value}`; the others return `{items,count}`.

## Approvals and waits

Create **Request approval**, run the sample, and open **Approvals**. Approve or reject to continue on the corresponding true/false handle. Pending requests also appear in the execution drawer. Only the execution owner's workspace can decide, and a decision can be submitted once. Requests cannot be decided after termination. Delays and pending decisions persist in PostgreSQL across application restarts without holding worker threads.

## Child workflows and loops

Create a reusable workflow with a Manual trigger. Add Subworkflow or Loop in another workflow and select it. A subworkflow receives your configured input. Each loop child receives:

```json
{"item":{"id":"example"},"index":0,"source":{"items":[{"id":"example"}]}}
```

Child results appear under `steps.loopId.items.0.steps.childStepId`. Inspect children through the execution drawer. Each child snapshots the selected workflow **when it is spawned**. Editing it later does not change an already-created child; a parent queued before a child edit may spawn the newer child version. Parent failure/cancellation requests cancellation of descendants. Empty loops succeed with zero items.

## Forms and tables

**Forms → Create form workflow** creates a typed contact form. Configure title and fields in its trigger, activate and save, then copy the public link. Supported types: text, email, number, checkbox. Only declared fields are collected. Treat the secret link like a webhook secret.

**Data tables** creates private tables. Each record has a unique key and JSON value. Add, edit or delete records in the UI, or use a Table step for get/list/upsert/delete. Get returns `{found,record}`; list returns `{items}` (latest 100). Upsert replaces the value for that key. Workflow writes consume the action budget; manual table edits do not belong to an execution budget.

## Integrations and AI

Save bearer tokens in **Credentials**. **Integrations** offers editable Slack message, GitHub issue and generic HTTP recipes. Select the token and set service-specific IDs and request fields. Slack's recipe enables `requireOk`, so an HTTP-200 response with `ok:false` fails. These are API templates, not OAuth app connections.

AI text uses a Chat Completions-compatible endpoint with `model` and a user `messages` entry. Configure endpoint, supported model ID, encrypted credential and prompt. The output is `{text,usage}`. Live requests can incur provider charges. Verification uses mocked provider responses; no paid request or external notification was sent. Local email goes to Mailpit at http://localhost:8025.

## Impact Lab

Open a saved workflow's **Impact Lab** or choose **Impact Lab** in the top navigation. Enter scenario input; preview its paths, outputs, recipients, destinations and writes without dispatching actions. HTTP, AI and table reads require a supplied mock or pinned output. For example:

```json
{"request":{"status":200,"body":{"ok":true}},"assistant":{"text":"Example summary","usage":{}}}
```

Keys match node IDs. A nested key can be `loopId[0]/childStepId`. External fixtures and assumed approval decisions are listed explicitly. Delays are not elapsed. Table writes are simulated, and record existence is not checked. It never creates real child executions. A fixture describes one scenario, not a guarantee about a provider's future response. A failed assertion is not implemented; inspect the preview and version differences.

Save a changed workflow and select an earlier baseline to compare both versions using identical input and fixtures. Changed node outputs and changed action plans are shown. **Versions → Restore** creates a new inactive version; existing execution snapshots remain intact.

In **Settings**, set an action budget (0–1000, default 100) and optional allowed HTTP hostnames. Every attempted HTTP/AI request, email and workflow table write reserves one action atomically against the **root** budget, including retries and loop children. Network policy violations dispatch no request. The ledger records reservations, including attempts that later fail. A budget counts action attempts, not cost. It is never reset by Resume. Older runs migrated from v1 have no historical action ledger.

Impact Lab combines simulation, version comparison and a live nested action limit. n8n and Zapier already have overlapping testing and safety capabilities; this combination is a proposed differentiator, not a verified exclusive invention.

## Recovery and history

**Executions** supports workflow-name search, status filtering and pages of 100 runs. **Resume failed steps** keeps completed steps and the original snapshot, resets failed/unstarted work, and resumes unfinished descendants. It refuses to resume while a child action lease is still active. Correct configuration errors by editing and starting a new run; Resume does not replace the failed run's definition or replenish its budget. **Replay** starts a new execution from the old snapshot and can repeat completed effects. Cancellation cannot undo an already-sent request or email.

Archive preserves workflows/history and disables future automatic triggers. Unarchive before activating or starting new runs. Folder and tag metadata can be searched in the workflow library. Workflows, tables, credentials, approvals and execution history belong to the signed-in user; collaborative team workspaces are outside this release.
