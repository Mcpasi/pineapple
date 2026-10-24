#!/usr/bin/env node
import { readFile, writeFile, mkdir } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { createPreviewHtml } from "./preview-renderer.mjs"
import { renderAuthoredPreview } from "./authored-preview.mjs"

const templateRoot = path.dirname(fileURLToPath(import.meta.url))
const systemRoot = path.resolve(templateRoot, "..")
const projectRoot = path.resolve(systemRoot, "..")

function insideSystem(relative) {
  const target = path.resolve(systemRoot, relative)
  const prefix = systemRoot.endsWith(path.sep) ? systemRoot : systemRoot + path.sep
  if (target !== systemRoot && !target.startsWith(prefix)) throw new Error("Design System path escapes its root: " + relative)
  return target
}

async function json(relative) {
  return JSON.parse(await readFile(insideSystem(relative), "utf8"))
}

async function text(relative) {
  return readFile(insideSystem(relative), "utf8")
}

function section(markdown, heading) {
  const expression = new RegExp("^## " + heading + "[ \\t]*\\r?\\n([\\s\\S]*?)(?=\\r?\\n## |(?![\\s\\S]))", "mi")
  return (markdown.match(expression)?.[1] ?? "").replace(/^- None specified\.$/m, "").trim()
}

function bullets(markdown, heading) {
  return section(markdown, heading).split("\n").map((line) => line.match(/^\s*-\s+(.*)$/)?.[1]?.trim()).filter(Boolean)
}

async function main() {
  const manifest = await json("manifest.json")
  const tokenDocument = await json(manifest.tokens || "tokens.json")
  const authoredSource = insideSystem("preview/source.html")
  try {
    const source = await readFile(authoredSource, "utf8")
    const destination = insideSystem(manifest.preview || "preview/index.html")
    await mkdir(path.dirname(destination), { recursive: true })
    await writeFile(destination, renderAuthoredPreview(source, tokenDocument), "utf8")
    console.log("Generated " + path.relative(projectRoot, destination).split(path.sep).join("/"))
    return
  } catch (error) {
    if (error.code !== "ENOENT") throw error
  }
  const components = await Promise.all((manifest.components || []).map(async (item) => {
    const markdown = await text(item.file)
    return {
      name: item.name,
      file: item.file,
      purpose: section(markdown, "Purpose"),
      variants: bullets(markdown, "Variants"),
      states: bullets(markdown, "States"),
      behavior: section(markdown, "Behavior"),
      tokens: item.tokens || bullets(markdown, "Tokens").map((token) => token.replaceAll("`", "")),
    }
  }))
  const patterns = await Promise.all((manifest.patterns || []).map(async (item) => {
    const markdown = await text(item.file)
    return {
      name: item.name,
      file: item.file,
      purpose: section(markdown, "Purpose"),
      guidance: section(markdown, "Guidance"),
      composition: bullets(markdown, "Composition"),
      tokens: item.tokens || [],
    }
  }))
  const html = createPreviewHtml({ manifest, tokens: tokenDocument, components, patterns })
  const destination = insideSystem(manifest.preview || "preview/index.html")
  await mkdir(path.dirname(destination), { recursive: true })
  await writeFile(destination, html, "utf8")
  console.log("Generated " + path.relative(projectRoot, destination).split(path.sep).join("/"))
}

main().catch((error) => {
  console.error("Could not generate Design System preview: " + (error instanceof Error ? error.message : String(error)))
  process.exitCode = 1
})
