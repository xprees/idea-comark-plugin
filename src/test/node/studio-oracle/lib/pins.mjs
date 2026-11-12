// Pinned files of the Nuxt Studio oracle: the sha256 of every nuxt-studio 1.8.0, comark 0.7.0 and js-yaml 5.5.0 file the
// recorders run, as published on npm. Shared by record.mjs and record-db-vectors.mjs; a different install stops recording
// unless --allow-other-studio / --allow-other-renderer is passed.
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const studioRuntime = path.join(here, 'node_modules/nuxt-studio/dist/module/runtime')

// Every Studio runtime file the recorders load (the hook stand-ins in lib/ are not Studio files).
export const STUDIO_FILES = [
  'utils/document/generate.js', 'utils/document/legacy.js', 'utils/document/schema.js',
  'utils/document/utils.js', 'utils/document/tree.js', 'utils/collection.js', 'utils/object.js',
  'utils/source.js', 'utils/url.js', 'types/content.js',
]
export const sha256 = (buf) => createHash('sha256').update(buf).digest('hex')
// sha256 of the nuxt-studio@1.8.0 files as published on npm. A different install (another version, a patched
// copy) stops the recording unless --allow-other-studio is passed; the hashes written always describe what ran.
export const EXPECTED_STUDIO_FILES = {
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
// The renderer itself: every comark 0.7.0 and js-yaml 5.5.0 file that `comark/render` and comark's internal/yaml.js load
// (comark's utils also load `entities`, whose decoder renderMarkdown does not call). sha256 as published on npm.
export const EXPECTED_RENDERER_FILES = {
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
// Hashes the installed files and exits the process when they are not the pinned ones (unless allowed by `args`).
// Returns { studioFiles, rendererFiles } describing what actually runs.
export function checkPins(args) {
  const studioFiles = Object.fromEntries(STUDIO_FILES.map((rel) => [
    'nuxt-studio/dist/module/runtime/' + rel, sha256(fs.readFileSync(path.join(studioRuntime, rel))),
  ]))
  const changed = STUDIO_FILES.filter((rel) => studioFiles['nuxt-studio/dist/module/runtime/' + rel] !== EXPECTED_STUDIO_FILES[rel])
  if (changed.length && !args.includes('--allow-other-studio')) {
    console.error(`nuxt-studio files differ from 1.8.0: ${changed.join(', ')} (run npm ci, or pass --allow-other-studio)`)
    process.exit(2)
  }
  const rendererFiles = Object.fromEntries(Object.keys(EXPECTED_RENDERER_FILES)
    .map((rel) => [rel, sha256(fs.readFileSync(path.join(here, 'node_modules', rel)))]))
  const changedRenderer = Object.keys(rendererFiles).filter((rel) => rendererFiles[rel] !== EXPECTED_RENDERER_FILES[rel])
  if (changedRenderer.length && !args.includes('--allow-other-renderer')) {
    console.error(`comark/js-yaml files differ from comark 0.7.0 / js-yaml 5.5.0: ${changedRenderer.join(', ')} (run npm ci, or pass --allow-other-renderer)`)
    process.exit(2)
  }
  return { studioFiles, rendererFiles }
}
