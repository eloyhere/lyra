#!/usr/bin/env node
/**
 * External conformance check for JSON exported by the Kotlin AMP port.
 *
 * The schemas and canonical implementation come from the reference
 * repository, so this checks interoperability, not a Kotlin-only opinion.
 *
 * Usage:
 *   node tools/amp-conformance/validate-wire.mjs
 *
 * Input: app/build/amp-wire/*.json
 * Each file is either one object or an array of objects.
 * Optional: app/build/amp-wire/manifest-public-key.b64
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { verify } from "node:crypto";

const reference = resolve(process.env.AMP_REFERENCE ?? "R:/project/attention-manifest-protocol");
const schemaDir = join(reference, "docs", "schemas");
const wireDir = resolve(process.env.AMP_WIRE_DIR ?? "app/build/amp-wire");
const canonicalUrl = pathToFileURL(join(reference, "src", "semantic", "canonical.ts")).href;
const { canonical, digestOf } = await import(canonicalUrl);

if (!existsSync(wireDir)) {
    console.error(`missing ${wireDir}; run the wire export test first`);
    process.exit(2);
}

const schemaCache = new Map();
function loadSchema(file) {
    if (!schemaCache.has(file)) {
        schemaCache.set(file, JSON.parse(readFileSync(file, "utf8")));
    }
    return schemaCache.get(file);
}

function resolveRef(ref, baseFile) {
    const [filePart, pointer] = ref.split("#");
    const targetFile = filePart ? resolve(dirname(baseFile), filePart) : baseFile;
    const target = loadSchema(targetFile);
    let node = target;
    if (pointer) {
        for (const raw of pointer.split("/").filter(Boolean)) {
            const key = decodeURIComponent(raw).replace(/~1/g, "/").replace(/~0/g, "~");
            node = node == null ? undefined : node[key];
        }
    }
    return { node, file: targetFile };
}

const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const typeOf = (value) => Array.isArray(value) ? "array" : value === null ? "null" : typeof value;
const dateTime = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;

function typeMatches(expected, data) {
    if (Array.isArray(expected)) return expected.some((type) => typeMatches(type, data));
    switch (expected) {
        case "object": return isObject(data);
        case "array": return Array.isArray(data);
        case "string": return typeof data === "string";
        case "number": return typeof data === "number" && Number.isFinite(data);
        case "integer": return typeof data === "number" && Number.isInteger(data);
        case "boolean": return typeof data === "boolean";
        case "null": return data === null;
        default: return true;
    }
}

function validate(schema, data, baseFile, path, errors) {
    if (schema == null || typeof schema !== "object") return;
    if (schema.$ref) {
        const { node, file } = resolveRef(schema.$ref, baseFile);
        if (node === undefined) {
            errors.push(`${path}: unresolved $ref ${schema.$ref}`);
            return;
        }
        validate(node, data, file, path, errors);
        return;
    }
    if (schema.oneOf) {
        const branchErrors = [];
        let matched = 0;
        for (const branch of schema.oneOf) {
            const next = [];
            validate(branch, data, baseFile, path, next);
            if (next.length === 0) matched += 1;
            else branchErrors.push(next);
        }
        if (matched !== 1) {
            errors.push(`${path}: oneOf matched ${matched} branches`);
            if (matched === 0) {
                const shortest = branchErrors.sort((a, b) => a.length - b.length)[0] ?? [];
                errors.push(...shortest.slice(0, 4).map((item) => `  ${item}`));
            }
        }
        return;
    }
    if (schema.anyOf) {
        const ok = schema.anyOf.some((branch) => {
            const next = [];
            validate(branch, data, baseFile, path, next);
            return next.length === 0;
        });
        if (!ok) errors.push(`${path}: no anyOf branch matched`);
        return;
    }
    if (schema.allOf) {
        for (const branch of schema.allOf) validate(branch, data, baseFile, path, errors);
        return;
    }
    if (schema.const !== undefined && canonical(schema.const) !== canonical(data)) {
        errors.push(`${path}: expected const ${JSON.stringify(schema.const)}`);
    }
    if (schema.enum && !schema.enum.some((item) => canonical(item) === canonical(data))) {
        errors.push(`${path}: not in enum`);
    }
    if (schema.type && !typeMatches(schema.type, data)) {
        errors.push(`${path}: expected ${schema.type}, got ${typeOf(data)}`);
        return;
    }
    if (typeof data === "number") {
        if (schema.minimum !== undefined && data < schema.minimum) errors.push(`${path}: below minimum`);
        if (schema.maximum !== undefined && data > schema.maximum) errors.push(`${path}: above maximum`);
    }
    if (typeof data === "string") {
        if (schema.minLength !== undefined && [...data].length < schema.minLength) errors.push(`${path}: too short`);
        if (schema.pattern && !new RegExp(schema.pattern).test(data)) errors.push(`${path}: pattern mismatch`);
        if (schema.format === "date-time" && !dateTime.test(data)) errors.push(`${path}: bad date-time`);
    }
    if (Array.isArray(data)) {
        if (schema.minItems !== undefined && data.length < schema.minItems) errors.push(`${path}: too few items`);
        if (schema.maxItems !== undefined && data.length > schema.maxItems) errors.push(`${path}: too many items`);
        if (schema.items) data.forEach((item, index) => validate(schema.items, item, baseFile, `${path}[${index}]`, errors));
        return;
    }
    if (isObject(data)) {
        for (const key of schema.required ?? []) {
            if (!Object.prototype.hasOwnProperty.call(data, key)) errors.push(`${path}: missing required "${key}"`);
        }
        const properties = schema.properties ?? {};
        for (const [key, value] of Object.entries(data)) {
            if (properties[key]) validate(properties[key], value, baseFile, `${path}.${key}`, errors);
            else if (schema.additionalProperties === false) errors.push(`${path}: unexpected property "${key}"`);
        }
    }
}

function verifyLedgerSnapshot(snapshot) {
    const errors = [];
    if (snapshot.kind !== "ledger-snapshot") return errors;
    let prev = `sha256:${"0".repeat(64)}`;
    snapshot.entries.forEach((entry, index) => {
        if (entry.seq !== index) errors.push(`entry ${index}: seq ${entry.seq}`);
        if (entry.prev !== prev) errors.push(`entry ${index}: prev mismatch`);
        const expected = digestOf({ seq: entry.seq, kind: entry.kind, payload: entry.payload, prev: entry.prev });
        if (entry.hash !== expected) errors.push(`entry ${index}: hash mismatch`);
        prev = entry.hash;
    });
    if (snapshot.count !== snapshot.entries.length) errors.push("count mismatch");
    if (snapshot.tip !== prev) errors.push("tip mismatch");
    return errors;
}

const kindToSchema = {
    frame: "frame.schema.json",
    receipt: "receipt.schema.json",
    event: "receipt.schema.json",
    manifest: "manifest.schema.json",
    "ledger-snapshot": "ledger-snapshot.schema.json",
    checkpoint: "compaction.schema.json",
    "prune-plan": "compaction.schema.json",
};

const publicKeyFile = join(wireDir, "manifest-public-key.b64");
const publicKey = existsSync(publicKeyFile)
    ? (await import("node:crypto")).createPublicKey({
        key: Buffer.from(readFileSync(publicKeyFile, "utf8").trim(), "base64"),
        format: "der",
        type: "spki",
    })
    : undefined;

const failures = [];
let checked = 0;
for (const file of readdirSync(wireDir).filter((name) => name.endsWith(".json")).sort()) {
    const docs = JSON.parse(readFileSync(join(wireDir, file), "utf8"));
    for (const [index, doc] of (Array.isArray(docs) ? docs : [docs]).entries()) {
        const label = Array.isArray(docs) ? `${file}[${index}]` : file;
        const schemaName = kindToSchema[doc.kind];
        if (!schemaName) {
            failures.push(`${label}: unknown kind ${JSON.stringify(doc.kind)}`);
            continue;
        }
        const schemaPath = join(schemaDir, schemaName);
        const errors = [];
        validate(loadSchema(schemaPath), doc, schemaPath, "$", errors);

        if (doc.kind === "frame" && typeof doc.digest === "string") {
            const { digest, ...body } = doc;
            const expected = digestOf(body);
            if (digest !== expected) errors.push(`$.digest: ${digest} != ${expected}`);
        }
        if (doc.kind === "manifest") {
            const { digest, signatures, ...body } = doc;
            const expected = digestOf(body);
            if (digest !== expected) errors.push(`$.digest: ${digest} != ${expected}`);
            if (publicKey) {
                const self = (signatures ?? []).find((signature) => signature.role === "self");
                if (!self) {
                    errors.push("$.signatures: missing self signature");
                } else {
                    const signed = { ...body, digest };
                    const ok = verify(null, Buffer.from(canonical(signed), "utf8"), publicKey, Buffer.from(self.sig, "base64url"));
                    if (!ok) errors.push("$.signatures[0]: signature does not verify");
                }
            }
        }
        errors.push(...verifyLedgerSnapshot(doc));

        if (errors.length > 0) {
            failures.push(`${label} (${schemaName})`);
            failures.push(...errors.slice(0, 12).map((error) => `  ${error}`));
        } else {
            checked += 1;
        }
    }
}

if (failures.length > 0) {
    console.error(failures.join("\n"));
    process.exit(1);
}
console.log(`wire conformance: ${checked} object(s) validated`);
