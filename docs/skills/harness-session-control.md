---
name: harness-session-control
description: Drive DeepSeek harness sessions over its HTTP API - stopping a running agent and the work it started, steering a live turn, and listing or cancelling sessions. Includes the auth bootstrap and the exact request envelope, both verified against a live harness.
whenToUse: When you need to stop a running agent, stop work an agent started elsewhere, send instructions into a running turn, find a session by id, or call any harness API endpoint from a script.
---

# Harness session control

The harness serves its whole RPC surface over HTTP. These shapes were verified against a running harness rather than inferred, because no part of them is guessable.

## Stopping a running agent - do it in this order

**The order is the whole point.** A live agent will simply re-do whatever you undo behind it.

1. **Stop the agent** - `session/cancel` (below).
2. **Confirm it stopped** - list sessions and check nothing is `running: true` except your own.
3. **Kill the work it started** - `session/cancel` stops the *agent*, never the processes it launched over ssh. Those survive, orphaned.
4. **Then** put the machine back to sleep.

Skipping 1 makes 3 pointless: kill a generation, hibernate the box, and the still-running agent wakes it again and starts over. That is not hypothetical - it happened.

```bash
# 3. the work, on the machine that ran it
pkill -f gen_hy3d_vram.py; pkill -f gen_hy3d
nvidia-smi --query-gpu=utilization.gpu,memory.used,power.draw --format=csv,noheader
```

## Auth, once

The token exchange is automatic and needs nothing from the user:

```
GET  <origin>/auth.json      public asset, no cookie
     { "urls": { "<origin>": "https://.../?token=..." } }
GET  that token URL          303 -> Set-Cookie, valid 30 days
```

Keep the cookie with `curl -c`. It outlives harness restarts. From a phone, **the Tailscale Serve hostname is the origin** (`https://<machine>.<tailnet>.ts.net`), not the raw `100.x` address - the raw address fails on TLS.

## The request envelope

Every call, no exceptions:

```
POST /api/<endpoint>                       endpoint with SLASHES, not dots
Content-Type: application/json
Cookie: <from the token exchange>

{ "type": "client-request",
  "rpcId": "<uuid>",
  "method": "<endpoint>",                  must equal the endpoint string exactly
  "payload": { "args": { "<field>": { ... } } } }

-> { "result": { "ok": true, "value": { ... } } }
```

Three things that are easy to get wrong and produce unhelpful errors:

- **Dots 404.** `/api/session.list` does not exist; `/api/session/list` does.
- **The payload is double-wrapped**, and the field is named per endpoint: `_request` for `session/list`, `request` for `session/cancel`, `create` and `prompt`.
- **Nothing is returned unless `result.ok`** - failures arrive beside it as a structured error, not as an HTTP status.

## Endpoints

| endpoint | args field | request | returns |
|---|---|---|---|
| `session/list` | `_request` | `{}` | `{ items: [...] }` - every session with projections |
| `session/cancel` | `request` | `{ sessionId }` | `{ accepted: true }` |
| `session/create` | `request` | `{ cwd? }` | `{ sessionId, agentPreset }` |
| `session/prompt` | `request` | see below | `{ accepted: true }` |

### Stopping a session

```bash
SID=session-...
curl -s -b cookies.txt -X POST -H 'content-type: application/json' \
  -d "{\"type\":\"client-request\",\"rpcId\":\"stop-1\",\"method\":\"session/cancel\",
       \"payload\":{\"args\":{\"request\":{\"sessionId\":\"$SID\"}}}}" \
  "$HOST/api/session/cancel"
```

### Sending instructions into a session

```json
{ "requestId": "<uuid, client-minted, REQUIRED>",
  "sessionId": "session-...",
  "mode": "queue" | "steer",
  "content": [ { "type": "text", "text": "..." } ] }
```

- **`steer`** delivers into a **running** turn. This is how to course-correct an agent mid-task - and it is the only way to give it knowledge it lacks, such as where a handoff file must land.
- **`queue`** waits for the current turn to finish.
- **Omitting `requestId` fails with `input-invalid ... "request" failed boundary validation`**, naming no field. It is required and client-minted.

## Gotchas

**A steer arriving with no live turn STARTS one.** Useful for waking an agent with new instructions; surprising when the intent was to stop it, because a queued steer quietly undoes a cancel. If a session reads `running: true` right after being cancelled, this is why - cancel again once the queue is empty.

**Cancelling is not instantaneous.** Confirm via `session/list` before assuming it worked. A count of running sessions is the quick check, though your own session will be among them.

**Reading a session's own fields from `session/list` is fiddly.** The response is a large nested JSON document, and session ids also appear inside other sessions' turn text. Parse it properly - or match on the id together with the fields immediately following it, since a naive search finds the first mention, which may be a quote in someone's chat.
