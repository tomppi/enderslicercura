# Published skills

Sanitized copies of the assistant's skill files - the runbooks it follows when asked to drive hardware. See [`../AI_ASSISTANT.md`](../AI_ASSISTANT.md) for how they fit into the pipeline.

| file | what it covers |
|---|---|
| [`blender-mcp-engine.md`](blender-mcp-engine.md) | Driving the Blender engine embedded in the app over its MCP socket, and the STL export handoff into the slicer UI |
| [`image-to-3d-model.md`](image-to-3d-model.md) | Photograph to printable STL: the generator, its settings, post-processing, validation and the delivery handoff |
| [`gpu-box-power.md`](gpu-box-power.md) | Hibernating and waking the GPU box over Wake-on-LAN, and why each piece of that configuration is needed |
| [`harness-session-control.md`](harness-session-control.md) | Driving harness sessions over the HTTP API: the auth bootstrap, the request envelope, and stopping work that has already started |

## Why these are copies

The live skills live in `.dsh/skills/` and are **gitignored**, because driving real hardware needs real details: machine addresses, a login, a MAC for the wake packet, device identifiers. Those must not be published.

These copies are the same text with every one of those values substituted. A small script does the substitution so the two cannot drift apart silently:

```bash
node scripts/publish-skills.mjs
```

It reads the substitution map from `.dsh/publish-map.json` - itself gitignored, for the obvious reason - and rewrites everything in this directory. **Committing a new map entry is never necessary and never safe**; the script is what is versioned, not the values.

## Address conventions

Substituted values use reserved ranges so they are recognisable as examples and can never collide with a real network:

| range | standard | used for |
|---|---|---|
| `192.0.2.0/24` | RFC 5737 (TEST-NET-1) | local network addresses |
| `198.51.100.0/24` | RFC 5737 (TEST-NET-2) | the point-to-point link between the waking device and the GPU box |
| `100.64.0.0/10` | RFC 6598 (shared address space) | tailnet addresses |
| `02:00:5e:10:00:01` | locally administered | the wake-on-LAN target's MAC |

Hostnames and logins appear as placeholders (`<user>`, `<you>`, `phone-host`, `<pi-password>`). The application's own package id is left as-is: it is public, and the commands that reference it would be useless without it.
