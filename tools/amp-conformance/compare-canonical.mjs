import { readFileSync, existsSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";

const reference = resolve(process.env.AMP_REFERENCE ?? "R:/project/attention-manifest-protocol");
const canonicalModule = resolve(reference, "src/semantic/canonical.ts");
if (!existsSync(canonicalModule)) {
    console.error(`reference implementation not found: ${canonicalModule}`);
    process.exit(2);
}
const { canonical, digestOf } = await import(pathToFileURL(canonicalModule).href);

const kotlinFile = resolve("app/build/amp-canonical-kotlin.json");
if (!existsSync(kotlinFile)) {
    console.error(`missing ${kotlinFile}; run :app:testDebugUnitTest first`);
    process.exit(2);
}

const entries = JSON.parse(readFileSync(kotlinFile, "utf8"));
const failures = [];
for (const entry of entries) {
    const parsed = JSON.parse(entry.canonical);
    const expected = canonical(parsed);
    if (expected !== entry.canonical) {
        failures.push({ file: entry.file, kind: "canonical", kotlin: entry.canonical, reference: expected });
    }
    const expectedDigest = digestOf(parsed);
    if (expectedDigest !== entry.digest) {
        failures.push({ file: entry.file, kind: "digest", kotlin: entry.digest, reference: expectedDigest });
    }
}

if (failures.length > 0) {
    console.error(JSON.stringify(failures, null, 2));
    process.exit(1);
}
console.log(`canonical/digest conformance: ${entries.length}/${entries.length}`);
