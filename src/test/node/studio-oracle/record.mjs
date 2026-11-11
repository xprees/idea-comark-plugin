#!/usr/bin/env node
// Records the Nuxt Studio 1.8.0 save-pipeline oracle used by the IDE's "Nuxt Studio" formatting style.
//
//   npm ci && node record.mjs [--out ../../testData/studio/oracle.json] [--repo ../../../..]
//
// Studio's document utilities are imported from the installed nuxt-studio@1.8.0 dist, unmodified.
// Only the two Nuxt-only specifiers they import are redirected by a module.register() resolve hook
// (lib/hooks.mjs): '@nuxt/content/runtime' and the useHostMeta composable. The sha256 of every Studio
// file that gets loaded is written to the output so a test can tell which Studio build produced it.
//
// Nuxt Content (3.16.x) parsing a file into the row Studio reads from its SQLite database is emulated
// by lib/nuxt-content-parse.mjs (see README.md for what is and is not faithful).
import { register } from 'node:module'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

register('./lib/hooks.mjs', import.meta.url)

const here = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def }
const repo = path.resolve(here, opt('--repo', '../../../..'))
const outFile = path.resolve(here, opt('--out', path.join(repo, 'src/test/testData/studio/oracle.json')))
const testData = path.join(repo, 'src/test/testData')

const DOC_ID = 'content/docs/page.md'
const MAX_INPUT = 8000 // inputs longer than this are cut at the last blank line before the limit

// ---- Studio modules (unmodified) -------------------------------------------------------------
const pkgDir = (name) => path.join(here, 'node_modules', name)
const studioRoot = pkgDir('nuxt-studio')
const studioRuntime = path.join(studioRoot, 'dist/module/runtime')
const studioUrl = (rel) => pathToFileURL(path.join(studioRuntime, rel)).href
const generate = await import(studioUrl('utils/document/generate.js'))
const legacy = await import(studioUrl('utils/document/legacy.js'))
const tree = await import(studioUrl('utils/document/tree.js'))
await import(studioUrl('utils/document/schema.js'))
await import(studioUrl('utils/document/utils.js'))
const { renderMarkdown } = await import('comark/render')
const { parseMarkdown } = await import('comark')
const comarkEmoji = (await import('comark/plugins/emoji')).default
const tocPlugin = (await import('comark/plugins/toc')).default
const { nuxtContentParse } = await import('./lib/nuxt-content-parse.mjs')

// Every Studio runtime file the imports above pulled in (the hook stand-ins are not Studio files).
const STUDIO_FILES = [
  'utils/document/generate.js', 'utils/document/legacy.js', 'utils/document/schema.js',
  'utils/document/utils.js', 'utils/document/tree.js', 'utils/collection.js', 'utils/object.js',
  'utils/source.js', 'utils/url.js', 'types/content.js',
]
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex')
const studioFiles = Object.fromEntries(STUDIO_FILES.map((rel) => [
  'nuxt-studio/dist/module/runtime/' + rel, sha256(fs.readFileSync(path.join(studioRuntime, rel))),
]))
// sha256 of the nuxt-studio@1.8.0 files as published on npm. A different install (another version, a patched
// copy) stops the recording unless --allow-other-studio is passed; the hashes written always describe what ran.
const EXPECTED_STUDIO_FILES = {
  'utils/document/generate.js': 'f39e653b48bfbfd70fa437f43a199be6b0b247cc8f01f75548458750868ae478',
  'utils/document/legacy.js': 'c9fddc2e2705b2007dd2f4b98b3777075dd5b18de928dfbf062330a794e41691',
  'utils/document/schema.js': '2e58813177953511054acd7146e67375ea130a0c3d59f99b513d96db8a246054',
  'utils/document/utils.js': 'd91a0b6da5d753197755f74dc273aa5f56eb2ace44a8748ec3d89926b93c9ef0',
  'utils/document/tree.js': 'c148ed566a39e6e9b39a126fd0260dc7b5f9bdece6b99e3efaf88acbeb1d9fea',
  'utils/collection.js': 'eb1b5bdfa3ab5b35f1367fba028ca48f3d4fae2caf8e70a254194529b0c07f27',
  'utils/object.js': 'cb36d73c8fdb15b52162bdfa7cb2acd9ef56a3d4e8292529204d6a440eb9c3b1',
  'utils/source.js': '82f9e7a745c5982991cf9f0d4be6ae2d4b6f9ed3ccda134cfb2432fd6372d557',
  'utils/url.js': '406129b863d449f17fb1b6da395e8b3ff8ce55cc61688f0d0be383c4ad26499c',
  'types/content.js': '8d539d8a200b4a00232e7d4d82cbd2f0e0167e23a24ae50f342ec1352a6bb586',
}
const changed = STUDIO_FILES.filter((rel) => studioFiles['nuxt-studio/dist/module/runtime/' + rel] !== EXPECTED_STUDIO_FILES[rel])
if (changed.length && !args.includes('--allow-other-studio')) {
  console.error(`nuxt-studio files differ from 1.8.0: ${changed.join(', ')} (run npm ci, or pass --allow-other-studio)`)
  process.exit(2)
}
const pkgVersion = (name) => JSON.parse(fs.readFileSync(path.join(pkgDir(name), 'package.json'), 'utf8')).version
const versions = Object.fromEntries(['nuxt-studio', 'comark', 'js-yaml', '@nuxtjs/mdc', 'remark-mdc', 'minimark', 'shiki']
  .map((n) => [n, pkgVersion(n)]))

// ---- the two save paths ----------------------------------------------------------------------
// D: file -> (emulated) Nuxt Content row -> host.document.db.get (host.js:177-188) -> contentFromMarkdownDocument
async function dbDocument(src, collectionType) {
  const row = await nuxtContentParse(DOC_ID, src, { collectionType, schema: [] })
  const fsPath = DOC_ID.split('/').slice(1).join('/')
  return legacy.ensureComarkBody(tree.sanitizeDocumentTree({ ...row, fsPath }, null))
}
const D = async (src, collectionType) => generate.contentFromMarkdownDocument(await dbDocument(src, collectionType))
// T: file -> documentFromContent (comark parse, Studio options) -> contentFromMarkdownDocument
const textDocument = (src, collectionType) => generate.documentFromContent(DOC_ID, src, { compress: true, collectionType })
const T = async (src, collectionType) => generate.contentFromMarkdownDocument(await textDocument(src, collectionType))

const safe = async (fn) => { try { return await fn() } catch (e) { return { error: String((e && e.message) || e).split('\n')[0] } } }
const isStr = (v) => typeof v === 'string'

// ---- JSON encoding of values JSON cannot hold (YAML dates, .nan/.inf, undefined) ---------------
function encode(v) {
  if (v instanceof Date) return { $type: 'date', value: Number.isNaN(v.getTime()) ? 'Invalid Date' : v.toISOString() }
  if (typeof v === 'number' && !Number.isFinite(v)) return { $type: 'number', value: String(v) }
  if (typeof v === 'number' && Object.is(v, -0)) return { $type: 'number', value: '-0' }
  if (v === undefined) return { $type: 'undefined' }
  if (typeof v === 'bigint') return { $type: 'bigint', value: String(v) }
  if (Array.isArray(v)) return v.map(encode)
  if (v && typeof v === 'object') {
    const o = {}
    for (const [k, x] of Object.entries(v)) o[k] = encode(x)
    return o
  }
  return v
}
function decode(v) {
  if (Array.isArray(v)) return v.map(decode)
  if (v && typeof v === 'object') {
    if (typeof v.$type === 'string' && Object.keys(v).every((k) => k === '$type' || k === 'value')) {
      if (v.$type === 'date') return new Date(v.value)
      if (v.$type === 'number') return Number(v.value)
      if (v.$type === 'undefined') return undefined
      if (v.$type === 'bigint') return BigInt(v.value)
    }
    const o = {}
    for (const [k, x] of Object.entries(v)) o[k] = decode(x)
    return o
  }
  return v
}
// Drops parse positions ($.line, only set by comark when streaming) from a comark node.
function dropPositions(node) {
  if (!Array.isArray(node)) return node
  const [tag, attrs, ...children] = node
  let a = attrs
  if (attrs && typeof attrs === 'object' && attrs.$ && typeof attrs.$ === 'object' && 'line' in attrs.$) {
    const { line: _line, ...rest } = attrs.$
    a = { ...attrs }
    if (Object.keys(rest).length) a.$ = rest
    else delete a.$
  }
  return [tag, a, ...children.map(dropPositions)]
}

// Comark tree as renderMarkdown receives it on the TEXT path (generate.js:121-129).
async function textTree(src, collectionType) {
  const doc = await textDocument(src, collectionType)
  const t = legacy.unbindComarkTree(legacy.comarkTreeFromLegacyDocument(doc))
  return { frontmatter: t.frontmatter || {}, meta: t.meta || {}, nodes: t.nodes.map(dropPositions) }
}
async function renderTree(encodedTree, frontmatter) {
  const body = { frontmatter: decode(frontmatter), meta: decode(encodedTree.meta), nodes: decode(encodedTree.nodes) }
  return legacy.padBlankLinesInComponents(await renderMarkdown(body, { blockAttributesStyle: 'frontmatter' })) + '\n'
}
// Same comark parse as documentFromMarkdownContent but without the shiki plugin, to measure its effect.
async function treeWithoutShiki(src) {
  const t = await parseMarkdown(src, {
    autoClose: false, autoUnwrap: true, linkify: false,
    plugins: [comarkEmoji(), tocPlugin({ depth: 2, searchDepth: 2, title: '', links: [] })],
  })
  return legacy.unbindComarkTree(t).nodes.map(dropPositions)
}

// ---- corpus ----------------------------------------------------------------------------------
function cap(src) {
  if (src.length <= MAX_INPUT) return { input: src }
  const cut = src.lastIndexOf('\n\n', MAX_INPUT)
  return { input: src.slice(0, cut > 0 ? cut + 1 : MAX_INPUT), truncatedFrom: src.length }
}
const walk = (d) => fs.readdirSync(d, { withFileTypes: true })
  .flatMap((e) => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)])
const items = []
const corpusDir = path.join(here, 'corpus')
for (const f of fs.readdirSync(corpusDir).filter((f) => /\.mdc?$/.test(f)).sort()) {
  items.push({ name: 'handwritten/' + f, source: 'handwritten', collectionType: f.includes('.data.') ? 'data' : 'page', src: fs.readFileSync(path.join(corpusDir, f), 'utf8') })
}
for (const f of walk(testData).filter((f) => /\.(md|mdc)$/.test(f)).sort()) {
  items.push({ name: 'testData/' + path.relative(testData, f), source: 'testData', collectionType: 'page', src: fs.readFileSync(f, 'utf8') })
}
const cases = JSON.parse(fs.readFileSync(path.join(testData, 'formatter/differential/cases.json'), 'utf8'))
cases.forEach((c, i) => {
  items.push({ name: `case/${String(i).padStart(3, '0')} ${c.name}`, source: 'differential', collectionType: 'page', src: c.input })
})

// ---- record ----------------------------------------------------------------------------------
const records = []
let shikiTreeEffect = 0
const shikiEffectExamples = []
for (const it of items) {
  const { input, truncatedFrom } = cap(it.src)
  const ct = it.collectionType
  const db = await safe(() => D(input, ct))
  const text = await safe(() => T(input, ct))
  const textOfDb = isStr(db) ? await safe(() => T(db, ct)) : null
  const db2 = isStr(db) ? await safe(() => D(db, ct)) : null
  const text2 = isStr(text) ? await safe(() => T(text, ct)) : null
  const r = { name: it.name, source: it.source, input }
  if (truncatedFrom) r.truncatedFrom = truncatedFrom
  r.collectionType = ct
  r.db = db
  r.text = text
  r.textOfDb = textOfDb
  r.dbStable = isStr(db) && db2 === db
  r.textStable = isStr(text) && text2 === text
  if (isStr(db) && isStr(db2) && db2 !== db) r.db2 = db2
  if (isStr(text) && isStr(text2) && text2 !== text) r.text2 = text2
  const t = await safe(() => textTree(input, ct))
  if (t.error) {
    r.comarkTree = null
    r.frontmatter = null
    r.renderedFromTree = t
  } else {
    r.comarkTree = { meta: encode(t.meta), nodes: encode(t.nodes) }
    r.frontmatter = encode(t.frontmatter)
    r.renderedFromTree = await safe(() => renderTree(r.comarkTree, r.frontmatter))
    const plain = await safe(() => treeWithoutShiki(input))
    if (!plain.error && JSON.stringify(encode(plain)) !== JSON.stringify(r.comarkTree.nodes)) {
      shikiTreeEffect++
      if (shikiEffectExamples.length < 5) shikiEffectExamples.push(it.name)
    }
  }
  records.push(r)
}

const count = (f) => records.filter(f).length
const summary = {
  cases: records.length,
  bySource: Object.fromEntries(['handwritten', 'testData', 'differential'].map((s) => [s, count((r) => r.source === s)])),
  dbErrors: count((r) => !isStr(r.db)),
  textErrors: count((r) => !isStr(r.text)),
  dbUnchanged: count((r) => r.db === r.input),
  textUnchanged: count((r) => r.text === r.input),
  dbStable: count((r) => r.dbStable),
  textStable: count((r) => r.textStable),
  textOfDbEqualsDb: count((r) => isStr(r.db) && r.textOfDb === r.db),
  renderedFromTreeEqualsText: count((r) => isStr(r.text) && r.renderedFromTree === r.text),
  shikiTreeEffect,
}
const output = {
  description: 'Nuxt Studio 1.8.0 save-pipeline oracle. Re-record with `npm ci && node record.mjs` in src/test/node/studio-oracle; see its README.md.',
  documentId: DOC_ID,
  versions,
  studioFiles,
  shiki: {
    themes: { default: 'github-light', dark: 'github-dark', light: 'github-light' },
    note: "Studio passes theme names that comark's shiki plugin has no loader for, so highlighting throws and the plugin's catch branch only sets class=\"shiki\" on <pre> (renderMarkdown drops it). " +
      `Comparing with a parse without the plugin, the tree differs in ${shikiTreeEffect} case(s)` +
      (shikiEffectExamples.length ? ` (e.g. ${shikiEffectExamples.join(', ')})` : '') + '.',
  },
  summary,
  cases: records,
}
fs.mkdirSync(path.dirname(outFile), { recursive: true })
fs.writeFileSync(outFile, JSON.stringify(output, null, 1) + '\n')
console.log(JSON.stringify(summary, null, 1))
console.log('wrote', path.relative(process.cwd(), outFile), (fs.statSync(outFile).size / 1024).toFixed(0) + ' KiB')
