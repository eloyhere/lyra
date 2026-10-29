#!/usr/bin/env node
import { readFileSync, existsSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";

const reference = resolve(process.env.AMP_REFERENCE ?? "R:/project/attention-manifest-protocol");
const { canonical } = await import(pathToFileURL(resolve(reference, "src/semantic/canonical.ts")).href);
const kotlinFile = resolve("app/build/amp-numbers-kotlin.json");
if (!existsSync(kotlinFile)) {
    console.error(`missing ${kotlinFile}; run :app:testDebugUnitTest first`);
    process.exit(2);
}
const entries = JSON.parse(readFileSync(kotlinFile, "utf8"));
const failures = [];
for (const entry of entries) {
    const expected = canonical(entry.value);
    if (expected !== entry.formatted) {
        failures.push({ value: entry.value, kotlin: entry.formatted, reference: expected });
    }
}
if (failures.length > 0) {
    console.error(JSON.stringify(failures, null, 2));
    process.exit(1);
}
console.log(`number formatting: ${entries.length}/${entries.length}`);
