#!/usr/bin/env node
import { readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { renderAuthoredPreview } from "./authored-preview.mjs"

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..")
const source = await readFile(path.join(root, "preview", "source.html"), "utf8")
const manifest = JSON.parse(await readFile(path.join(root, "manifest.json"), "utf8"))
const tokens = JSON.parse(await readFile(path.join(root, manifest.tokens), "utf8"))
const target = path.resolve(root, manifest.preview)
if (!target.startsWith(root + path.sep) || !target.endsWith(".html")) throw new Error("Invalid preview destination")
await writeFile(target, renderAuthoredPreview(source, tokens), "utf8")
console.log(`Generated ${path.relative(path.dirname(root), target).split(path.sep).join("/")}`)
