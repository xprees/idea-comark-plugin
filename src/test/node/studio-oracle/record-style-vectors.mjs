#!/usr/bin/env node
// Records what the REAL Nuxt Studio 1.8.0 does to the IDE's Nuxt Studio code style output, next to oracle.json:
//
//   STUDIO_STYLE_INPUTS=$PWD/build/studio-style-inputs.json ./gradlew test --tests '*StudioStyleOracleTest*'  (writes the inputs)
//   npm ci && node record-style-vectors.mjs --in ../../../../build/studio-style-inputs.json [--out ../../testData/studio/style-vectors.json]
//
// The inputs are y = StudioStyle.format(x) for every oracle input and every text-vectors.json input (the Kotlin engine
// computes them; StudioStyleOracleTest writes them). For each y this records Studio's text path T(y) (the code editor's
// save) and its database path D(y) (a save of the document Nuxt Content parsed; the row is emulated by
// lib/nuxt-content-parse.mjs, as in record-db-vectors.mjs), under the collection the document was formatted for.
// StudioStyleOracleTest checks that the engine still writes every recorded y and categorizes, with exact counts, where real
// Studio still changes y: only inside the blocks the engine reports (kept, unstable, unverified), or elsewhere.
//
// Each save runs in a worker thread with a time limit (--timeout, 20 s): a few kept-verbatim inputs make the database path
// loop (Studio would hang on them too); those are recorded as {"error": "timeout"}. Deterministic: recording twice gives
// identical bytes.
import { register } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { Worker, isMainThread, parentPort } from 'node:worker_threads'

register('./lib/hooks.mjs', import.meta.url)

const here = path.dirname(fileURLToPath(import.meta.url))
const firstLine = (e) => String((e && e.message) || e).split('\n')[0]

// The collections the oracle formats under: a page collection with Nuxt Content's standard schema, or a data collection.
const CONFIGS = {
  pageDefault: { collectionType: 'page', schema: [] },
  data: { collectionType: 'data', schema: [] },
}

if (!isMainThread) {
  // ---- worker: the two save paths, exactly as record-db-vectors.mjs runs them -----------------------------------------
  const studioRuntime = path.join(here, 'node_modules/nuxt-studio/dist/module/runtime')
  const studioUrl = (rel) => pathToFileURL(path.join(studioRuntime, rel)).href
  const generate = await import(studioUrl('utils/document/generate.js'))
  const legacy = await import(studioUrl('utils/document/legacy.js'))
  const tree = await import(studioUrl('utils/document/tree.js'))
  const { nuxtContentParse } = await import('./lib/nuxt-content-parse.mjs')
  async function dbDocument(src, id, config) {
    let row
    try {
      row = await nuxtContentParse(id, src, { collectionType: config.collectionType, schema: config.schema })
    } catch (e) {
      throw new Error('not in database: ' + firstLine(e))
    }
    const fsPath = id.split('/').slice(1).join('/')
    return legacy.ensureComarkBody(tree.sanitizeDocumentTree({ ...row, fsPath }, null))
  }
  const PATHS = {
    D: async (src, id, config) => generate.contentFromMarkdownDocument(await dbDocument(src, id, config)),
    T: async (src, id, config) =>
      generate.contentFromMarkdownDocument(await generate.documentFromContent(id, src, { compress: true, collectionType: config.collectionType })),
  }
  parentPort.on('message', async ({ which, y, id, config }) => {
    let out
    try {
      out = await PATHS[which](y, id, CONFIGS[config])
    } catch (e) {
      out = { error: firstLine(e) }
    }
    parentPort.postMessage(out)
  })
} else {
  // ---- main: inputs, the worker with its time limit, output -----------------------------------------------------------
  const args = process.argv.slice(2)
  const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def }
  const repo = path.resolve(here, '../../../..')
  const inFile = path.resolve(process.cwd(), opt('--in', path.join(repo, 'build/studio-style-inputs.json')))
  const outFile = path.resolve(process.cwd(), opt('--out', path.join(repo, 'src/test/testData/studio/style-vectors.json')))
  const TIMEOUT = Number(opt('--timeout', 20000))
  const PROGRESS = args.includes('--progress')

  const { checkPins } = await import('./lib/pins.mjs')
  const { studioFiles, rendererFiles } = checkPins(args)
  const pkgVersion = (name) => JSON.parse(fs.readFileSync(path.join(here, 'node_modules', name, 'package.json'), 'utf8')).version
  const versions = Object.fromEntries(['nuxt-studio', 'comark', 'js-yaml', '@nuxtjs/mdc', 'remark-mdc', 'minimark']
    .map((n) => [n, pkgVersion(n)]))

  let worker = null
  const run = (message) => new Promise((resolve) => {
    if (!worker) worker = new Worker(fileURLToPath(import.meta.url))
    const w = worker
    const timer = setTimeout(() => {
      w.removeAllListeners('message')
      w.terminate()
      worker = null
      resolve({ error: 'timeout' })
    }, TIMEOUT)
    w.once('message', (out) => {
      clearTimeout(timer)
      resolve(out)
    })
    w.postMessage(message)
  })

  const input = JSON.parse(fs.readFileSync(inFile, 'utf8'))
  const vectors = []
  const summary = { vectors: 0, tEqualsY: 0, dEqualsY: 0, dualFixedPoint: 0, tErrors: 0, dErrors: 0, timeouts: 0 }
  for (const [i, doc] of input.documents.entries()) {
    if (PROGRESS) process.stderr.write(`${i} ${doc.name}\n`)
    if (!CONFIGS[doc.config]) throw new Error('unknown configuration ' + doc.config)
    const t = await run({ which: 'T', y: doc.y, id: doc.id, config: doc.config })
    const d = await run({ which: 'D', y: doc.y, id: doc.id, config: doc.config })
    const v = { name: doc.name, config: doc.config, id: doc.id, y: doc.y, tEqualsY: t === doc.y, dEqualsY: d === doc.y }
    if (!v.tEqualsY) v.t = t
    if (!v.dEqualsY) v.d = d
    summary.vectors++
    if (v.tEqualsY) summary.tEqualsY++
    if (v.dEqualsY) summary.dEqualsY++
    if (v.tEqualsY && v.dEqualsY) summary.dualFixedPoint++
    if (typeof t !== 'string') summary.tErrors++
    if (typeof d !== 'string') summary.dErrors++
    if (t?.error === 'timeout' || d?.error === 'timeout') summary.timeouts++
    vectors.push(v)
  }
  if (worker) await worker.terminate()

  const header = {
    description: 'Real Nuxt Studio 1.8.0 save paths on the IDE\'s Nuxt Studio style output y = StudioStyle.format(x): T(y) (text path) ' +
      'and D(y) (database path), recorded by record-style-vectors.mjs; t / d present only where they differ from y.',
    versions,
    studioFiles,
    rendererFiles,
    summary,
  }
  const out = '{"header":' + JSON.stringify(header) + ',\n"vectors":[\n' + vectors.map((v) => JSON.stringify(v)).join(',\n') + '\n]}\n'
  fs.writeFileSync(outFile, out)
  console.log(JSON.stringify(summary))
}
