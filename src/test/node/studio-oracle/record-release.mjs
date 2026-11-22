// Runs one of the recorders against another nuxt-studio release than the root package.json pins (1.8.0).
//
//   node record-release.mjs <release> [--write-pins] <recorder.mjs> [recorder args...]
//   e.g. node record-release.mjs 1.8.2 record.mjs
//
// releases/<release>/ holds that release's package.json, package-lock.json and pins.json (sha256 of the Studio runtime files
// and of every comark / js-yaml file the renderer can load, as installed by npm ci). The recorders resolve their packages from
// the directory they live in, so they are copied into releases/<release>/stage/, next to a link to that release's node_modules,
// and write to src/test/testData/studio/<release>/ instead of src/test/testData/studio/.
//
// --write-pins (re)writes pins.json from the installed packages (after a deliberate npm install of new versions), then records.
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { sha256 } from './lib/pins.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const writePins = args.includes('--write-pins')
const [release, recorder, ...recorderArgs] = args.filter((a) => a !== '--write-pins')
if (!release || !recorder) {
  console.error('usage: node record-release.mjs <release> [--write-pins] <recorder.mjs> [args...]')
  process.exit(1)
}
const releaseDir = path.join(here, 'releases', release)
const pkg = JSON.parse(fs.readFileSync(path.join(releaseDir, 'package.json'), 'utf8'))
const modules = path.join(releaseDir, 'node_modules')
if (!fs.existsSync(modules)) {
  console.error(`${path.relative(process.cwd(), modules)} is missing: run npm ci in ${path.relative(process.cwd(), releaseDir)}`)
  process.exit(2)
}

const pinsFile = path.join(releaseDir, 'pins.json')
if (writePins) {
  const studioRuntime = path.join(modules, 'nuxt-studio/dist/module/runtime')
  const { STUDIO_FILES } = await import('./lib/pins.mjs')
  const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true })
    .flatMap((e) => (e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]))
  const hash = (file) => sha256(fs.readFileSync(file))
  const rendererFiles = [
    ...walk(path.join(modules, 'comark/dist')).filter((f) => f.endsWith('.js')),
    path.join(modules, 'js-yaml/dist/js-yaml.mjs'),
  ].map((f) => path.relative(modules, f).split(path.sep).join('/')).sort()
  const pins = {
    studio: pkg.studioOracle.studio,
    comark: pkg.studioOracle.comark,
    studioFiles: Object.fromEntries(STUDIO_FILES.map((rel) => [rel, hash(path.join(studioRuntime, rel))])),
    rendererFiles: Object.fromEntries(rendererFiles.map((rel) => [rel, hash(path.join(modules, rel))])),
  }
  fs.writeFileSync(pinsFile, JSON.stringify(pins, null, 2) + '\n')
  console.log(`wrote ${path.relative(process.cwd(), pinsFile)}`)
}

// Stage the recorders next to the release's packages.
const stage = path.join(releaseDir, 'stage')
fs.rmSync(stage, { recursive: true, force: true })
fs.mkdirSync(stage)
for (const entry of fs.readdirSync(here)) {
  if (entry.endsWith('.mjs') || entry.endsWith('.json') && !entry.startsWith('package') || entry === 'lib' || entry === 'corpus') {
    fs.cpSync(path.join(here, entry), path.join(stage, entry), { recursive: true })
  }
}
fs.symlinkSync(modules, path.join(stage, 'node_modules'), 'junction')

const repo = path.resolve(here, '../../../..')
const data = path.join(repo, 'src/test/testData/studio', release)
fs.mkdirSync(data, { recursive: true })
const run = spawnSync(process.execPath, [path.join(stage, recorder), ...recorderArgs], {
  stdio: 'inherit',
  env: {
    ...process.env,
    STUDIO_ORACLE_REPO: repo,
    STUDIO_ORACLE_DATA: data,
    STUDIO_ORACLE_PINS: pinsFile,
    STUDIO_ORACLE_STUDIO: pkg.studioOracle.studio,
    STUDIO_ORACLE_RENDERER: `comark ${pkg.studioOracle.comark} / js-yaml ${pkg.studioOracle.jsYaml}`,
  },
})
fs.rmSync(stage, { recursive: true, force: true })
process.exit(run.status ?? 1)
