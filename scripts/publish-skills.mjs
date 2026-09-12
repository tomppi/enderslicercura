/**
 * Publish sanitized copies of the workspace skills.
 *
 * The live skills in `.dsh/skills/` carry real machine addresses, credentials
 * and device identifiers, because that is what the agent needs to actually
 * drive the hardware. They are deliberately gitignored.
 *
 * This writes a shareable copy of each one to `docs/skills/`, with every real
 * value substituted for a placeholder or a documentation-range address
 * (RFC 5737 for LANs, 100.64.0.0/10 for a tailnet). The substitution map lives
 * in `.dsh/publish-map.json`, which is gitignored for the obvious reason - so
 * this script is safe to commit and the secrets never enter the repository.
 *
 * Usage: node scripts/publish-skills.mjs
 */
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const SKILLS = join(ROOT, ".dsh", "skills");
const MAP_FILE = join(ROOT, ".dsh", "publish-map.json");
const OUT = join(ROOT, "docs", "skills");

const map = JSON.parse(readFileSync(MAP_FILE, "utf8"));

/** Longest key first, so a shorter address cannot clobber a longer one. */
const keys = Object.keys(map).sort((a, b) => b.length - a.length);

function sanitize(text) {
  let out = text;
  for (const key of keys) {
    // Word boundaries keep 192.0.2.3 from matching inside 192.0.2.30.
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    out = out.replace(new RegExp("\\b" + escaped + "\\b", "g"), map[key]);
  }
  return out;
}

mkdirSync(OUT, { recursive: true });
let written = 0;
const report = [];
for (const name of readdirSync(SKILLS)) {
  const source = join(SKILLS, name, "SKILL.md");
  try {
    if (!statSync(source).isFile()) continue;
  } catch {
    continue;
  }
  const original = readFileSync(source, "utf8");
  const clean = sanitize(original);
  writeFileSync(join(OUT, name + ".md"), clean);
  written += 1;
  const leaked = keys.filter((k) => clean.includes(k));
  report.push({ name, bytes: clean.length, leaked });
}
console.log("wrote " + written + " skill(s) to docs/skills/");
for (const row of report) {
  console.log("  " + row.name.padEnd(24) + row.bytes + " bytes  " + (row.leaked.length ? "LEAK: " + row.leaked.join(", ") : "clean"));
}
