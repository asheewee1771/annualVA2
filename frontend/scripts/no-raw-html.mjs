// as-3/as-9: report content is rendered only as React text nodes. This gate fails the build when
// any source file reaches for raw HTML rendering.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const forbidden = [
  /dangerouslySetInnerHTML/,
  /\.innerHTML\s*=/,
  /\.outerHTML\s*=/,
  /insertAdjacentHTML\(/,
  /document\.write\(/,
];
const offenders = [];

function walk(dir) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      walk(path);
    } else if (/\.(tsx?|jsx?|mjs)$/.test(entry)) {
      const source = readFileSync(path, "utf8");
      for (const pattern of forbidden) {
        if (pattern.test(source)) offenders.push(`${path}: ${pattern}`);
      }
    }
  }
}

walk("src");
if (offenders.length > 0) {
  console.error("Raw HTML rendering is not allowed:\n" + offenders.join("\n"));
  process.exit(1);
}
console.log("no raw HTML rendering found");
