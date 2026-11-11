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
const { encode, decode } = await import('./lib/encode.mjs')

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
// The renderer itself: every comark 0.7.0 and js-yaml 5.5.0 file that `comark/render` and comark's internal/yaml.js load
// (comark's utils also load `entities`, whose decoder renderMarkdown does not call). sha256 as published on npm.
const EXPECTED_RENDERER_FILES = {
  'comark/dist/internal/frontmatter.js': 'fba9759556ce5f5efaa09a5edb7e855543aa11d01a7ad083f32fd8497d17ee7c',
  'comark/dist/internal/props-validation.js': 'f0c791b45709b1948e98ed5ea4ca79fd16f9392ea952448f62a8c347580696ac',
  'comark/dist/internal/stringify/attributes.js': '2d5b8f97e673ad4fa35b4605e0bc847c479e627bb5cc62fb0586a24b3cb005f6',
  'comark/dist/internal/stringify/fence.js': 'a41e7ac658f7d8c9a6862eaf338ecf6076bef62859c2eed5dd929c8344c9d53a',
  'comark/dist/internal/stringify/handlers/a.js': '5defe964993e141fda2c342ac3d6f8abd784f6df6fef8f79830cd1043b33f625',
  'comark/dist/internal/stringify/handlers/blockquote.js': '2d9bed6928d0aebb9f001047abf8a11ea51f28b9c859d5c320cd8fe2861a67b8',
  'comark/dist/internal/stringify/handlers/br.js': 'a16d7be4a2431447d9d2d947d0d0bca7921c13fdbca086d9fafe6637a368d95b',
  'comark/dist/internal/stringify/handlers/code.js': 'b327cda54c2000bbc82a2862629cbffd028c7c29beda71a49538d87a8e0b0217',
  'comark/dist/internal/stringify/handlers/comment.js': 'cf6d5ddd797ca891c26a7338f9dc46dd1b2100d0be8e9e1a55b6d9e65dfb0aa7',
  'comark/dist/internal/stringify/handlers/del.js': 'ab949f9e3505fa8236a9905ae756c0fd7f81e9c2220c7377a0fd8f5f2de4a08a',
  'comark/dist/internal/stringify/handlers/emphesis.js': '968e5eb4ef3074403fde6ee0e6f5c95b328b1f508c801e68318c2188c5ac1dd5',
  'comark/dist/internal/stringify/handlers/heading.js': 'be862a5b7ede09ee28479e892abc380ad353679b39f8cdfee571602c02a5474b',
  'comark/dist/internal/stringify/handlers/hr.js': '0818a7a741120d348ce38424fc1785619bdd9146176cf85742bf2c32b30c5ac6',
  'comark/dist/internal/stringify/handlers/html.js': '0c64be9ef9ae2da994d5639bf888757b2cc30b9a6191f66516626d4e801bef58',
  'comark/dist/internal/stringify/handlers/img.js': '6b517e833bc73f0f24788eae07da295269f47fc9bcdd361732e25b4196439da0',
  'comark/dist/internal/stringify/handlers/index.js': 'c9623a7212335adda9b177b4bf597613e2729827c2f560743a325b6513cb9da7',
  'comark/dist/internal/stringify/handlers/li.js': '5f9968788763b905584255d216812db317f2c6c953ced32b10472e7d6acd3bd0',
  'comark/dist/internal/stringify/handlers/math.js': '0341c2132ced56f90148c2efa97075b1a4e69039e66caef04fc70c6c6e8139c8',
  'comark/dist/internal/stringify/handlers/mdc.js': '83812f7be1c59d349d8168c0dc709716f696f11bd99fb3074e2b10b987f57ab4',
  'comark/dist/internal/stringify/handlers/mermaid.js': 'f1de42f51d52bc638fc6bdfd0d2247e1db9ac9226e581fd7d834d2bf0da09b1a',
  'comark/dist/internal/stringify/handlers/ol.js': 'f9b4782cf5ae6205d298425269493f92fd651a636f0b028f579a874c4670268e',
  'comark/dist/internal/stringify/handlers/p.js': 'dd6299f4518a65a0e5921f664761c6c809fc44f2d9c28ef617b952b9706a28ee',
  'comark/dist/internal/stringify/handlers/pre.js': 'ef0be33f97e8bc90002af360a9c488149becbaa65ed857d68d79ea7c8f384d53',
  'comark/dist/internal/stringify/handlers/strong.js': '54f4e7c220cad81c13412e7748a8d42c8df91c7bb4c56cf4ba542d9e86155604',
  'comark/dist/internal/stringify/handlers/table.js': 'd30f5fe25f490f5178cf71d12ddee21491400b5b2c33c651fe5a6b3a8c62bd34',
  'comark/dist/internal/stringify/handlers/template.js': 'd03f0e81adb10222b8e9e9a7e4094d8877251975da4be69b5f4e2f8c5a7e32d5',
  'comark/dist/internal/stringify/handlers/ul.js': 'a0584f6bdd4e7e53bf65f1cd11fb8f63163ae910d8681c65eeebe1baf59c0758',
  'comark/dist/internal/stringify/state.js': '2bacee2e42e6b97b9e7711068b21083b1a8afd696f0e13be00a2b3c45d7c0b86',
  'comark/dist/internal/yaml.js': '8a1de28bd480087481d9357690ce3d0b503ad3ef2b23f638b52a50e190be20bb',
  'comark/dist/render.js': '31488faabc708b5ca8621efc779273c8029d24f7820b7ad9ac1f18d9d8b0fec0',
  'comark/dist/utils/index.js': '5e3e642fcad6f7a4d38959854df64a4ac23fe5dd00d7eff5e4a31e6d857927db',
  'js-yaml/dist/js-yaml.mjs': '213470960d9c2ff1d01f9164e5017749bfc383d8a6ebb8683f35c76e8c6d052b',
}
const rendererFiles = Object.fromEntries(Object.keys(EXPECTED_RENDERER_FILES)
  .map((rel) => [rel, sha256(fs.readFileSync(path.join(here, 'node_modules', rel)))]))
const changedRenderer = Object.keys(rendererFiles).filter((rel) => rendererFiles[rel] !== EXPECTED_RENDERER_FILES[rel])
if (changedRenderer.length && !args.includes('--allow-other-renderer')) {
  console.error(`comark/js-yaml files differ from comark 0.7.0 / js-yaml 5.5.0: ${changedRenderer.join(', ')} (run npm ci, or pass --allow-other-renderer)`)
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
// Comark tree as renderMarkdown receives it on the DB path (contentFromMarkdownDocument, generate.js:121-129), or null when
// Studio finds no tree (it then writes "\n").
async function dbTree(src, collectionType) {
  const t = legacy.comarkTreeFromLegacyDocument(await dbDocument(src, collectionType))
  if (!t) return null
  const body = legacy.unbindComarkTree(t)
  return { frontmatter: body.frontmatter || {}, meta: body.meta || {}, nodes: body.nodes }
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
  const d = await safe(() => dbTree(input, ct))
  if (d === null || d.error) {
    r.dbTree = null
    r.dbFrontmatter = null
  } else {
    r.dbTree = { meta: encode(d.meta), nodes: encode(d.nodes) }
    r.dbFrontmatter = encode(d.frontmatter)
    r.renderedFromDbTree = await safe(() => renderTree(r.dbTree, r.dbFrontmatter))
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
  dbTrees: count((r) => r.dbTree !== null),
  renderedFromDbTreeEqualsDb: count((r) => isStr(r.db) && r.renderedFromDbTree === r.db),
  shikiTreeEffect,
}
const output = {
  description: 'Nuxt Studio 1.8.0 save-pipeline oracle. Re-record with `npm ci && node record.mjs` in src/test/node/studio-oracle; see its README.md.',
  documentId: DOC_ID,
  versions,
  studioFiles,
  rendererFiles,
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
