# Nuxt Studio oracle

Records how Nuxt Studio 1.8.0 rewrites Markdown when it saves a document. The result,
`src/test/testData/studio/oracle.json`, is the reference for the IDE's "Nuxt Studio" formatting style. It is committed, and
the Kotlin tests read it without needing Node.

## Re-recording

```sh
cd src/test/node/studio-oracle
npm ci
node record.mjs        # writes ../../testData/studio/oracle.json
```

Or run `./gradlew recordStudioOracle` from the repository root. It runs `npm ci` and then `record.mjs`, and it is not part of
`build` or `check`. Node 20.6 or newer is needed for `module.register()`. The output is deterministic: recording twice gives
identical bytes.

Options: `--out <file>` and `--repo <root>`. Recording stops if the installed nuxt-studio files do not match the published
1.8.0 hashes, unless you pass `--allow-other-studio`.

`record-vectors.mjs` (Gradle: `recordStudioVectors`) records three smaller files next to `oracle.json` for the Kotlin port of
the renderer:

- `yaml-vectors.json`: js-yaml 5.5.0 `dump(value, { indent: 2, lineWidth: -1 })` and comark's `stringifyYaml(value)` for
  about 2000 values (numeric-looking strings, YAML 1.1 booleans and nulls, dates, indicators, multi-line strings, unicode,
  numbers, empty and nested collections, shared and cyclic objects, which js-yaml writes with `&ref_N` anchors), each at the
  root, as a mapping value, as a sequence item and, for strings, as a key. `StudioYamlTest` checks `StudioYaml` against it.
- `render-vectors.json`: comark `renderMarkdown(tree, { blockAttributesStyle: 'frontmatter' })` for hand-made trees with
  attribute values the parsed corpus does not contain (numbers, booleans, `null`, objects, bindings, `$` html markers, odd
  `ol` starts, string and mixed `highlights`, comments without text, shared YAML objects), and for renderer branches the
  corpus does not reach. `StudioRenderVectorsTest` checks `StudioRenderer` against it.
- `render-random-vectors.json`: the same for 1500 trees from a seeded random generator, one vector per line. Recording twice
  gives the same trees.

`record-text-vectors.mjs` (Gradle: `recordStudioTextVectors`) records `text-vectors.json` for the Kotlin port of Studio's
text-path parse (`StudioTreeBuilder`, `StudioFormatter`): 5000 seeded random documents (3000 spliced and mutated from corpus
lines, 1000 strings of Markdown punctuation, 1000 with random YAML front matter or component props) plus 21 pathological
ones (markdown-it's nesting limit, long runs of delimiters, CRLF, a BOM, empty input). For each it stores the tree and front
matter `renderMarkdown` receives (`tree`, `frontmatter`) and the text Studio writes (`text`), or `{"error": ...}` where
Studio throws. One vector per line; recording twice gives identical bytes. `--count`, `--chars`, `--yaml`, `--seed`,
`--mutations` and `--out` record other sets (for example a larger local fuzz run).

`record-db-vectors.mjs` (Gradle: `recordStudioDbVectors`) records `db-vectors.json`, the ground truth for emulating the
database path D on documents the IDE writes. For every oracle input and every `text-vectors.json` input where T succeeds,
plus 128 front matter probes (`FM_PROBES` × `FM_BODIES`) and the inputs of `db-reproducers.json`, it takes y = T(x) (each
distinct y once) and records T(y) and,
under three collection configurations, D(y), T(D(y)) and D(D(y)). The configurations are `pageDefault` (a page collection
with Nuxt Content's standard schema), `pageSchema` (a page collection whose schema adds `date: z.date()`, `author`,
`tags`, `image`, `order: z.number()` and `draft: z.boolean().default(false)`) and `data` (a data collection); the content
id, whose file name decides an injected title, cycles through `files`. [DB-PATH.md](DB-PATH.md) describes every rule D
applies, and each vector lists the rules that explain D(y) != y (`lib/db-diff.mjs`). One vector per line:

| field | meaning |
|-------|---------|
| `y`, `source`, `inputs` | the document, the first input x with T(x) = y (`oracle:<case>`, `text-vectors:<index>`, `fm-probe:<i>.<j>`, `db-fuzz:<i>`, `db-probe:<i>`), and how many inputs gave it |
| `yStable`, `ty` | T(y) == y; T(y) when not (a string or `{"error": ...}`) |
| `<config>.id` | the content id used |
| `<config>.d` | D(y), present when it differs from y; `{"error": ...}` when Studio has no document (Nuxt Content's parse failed, or a value does not fit its column) |
| `<config>.dEqualsY`, `tOfDEqualsD`, `dOfDEqualsD` | D(y) == y, T(D(y)) == D(y), D(D(y)) == D(y) |
| `<config>.td`, `dd` | T(D(y)) and D(D(y)), present when they differ from D(y) |
| `<config>.rules` | the DB-PATH.md rule ids explaining D(y) != y |

The header's `summary` has the counts per configuration, over all y and over T-stable y (`onTStable`), and the most
frequent unexplained structure differences (`structureNotes`). `StudioDbVectorsTest` checks the file against the vectors
and prints the statistics; `StudioDbVectors` loads it. Recording takes about two minutes and is deterministic.

`db-reproducers.json` holds inputs for the Kotlin emulation of D (`StudioDbPath`, see [DB-PATH.md](DB-PATH.md#5-the-kotlin-emulation)):
`fuzz`, documents (each already a text-path output) on which a seeded local fuzz run of the emulation against this
recorder's D found a difference, and `probes`, hand-written documents with the constructs the emulation follows.
`StudioDbPathDifferentialTest` compares the emulation with every vector of `db-vectors.json`.

## What runs

All versions are pinned exactly in `package.json` and `package-lock.json`: nuxt-studio 1.8.0, comark 0.7.0, js-yaml 5.5.0,
@nuxtjs/mdc 0.23.3 (the version @nuxt/content 3.16.x resolves to), remark-mdc 3.11.1, minimark 0.2.0 and shiki 4.6.0.

- **Studio code is not modified.** `record.mjs` imports `generate.js`, `legacy.js`, `schema.js`, `utils.js` and `tree.js`
  (with what they import) from `node_modules/nuxt-studio/dist/module/runtime`. A `module.register()` resolve hook
  (`lib/hooks.mjs`) redirects only two Nuxt-only imports:
  - `@nuxt/content/runtime` goes to `lib/nuxt-content-runtime.mjs`, a verbatim copy of `compressTree`, `decompressTree` and
    `visit` from @nuxt/content 3.16.2 `dist/runtime/index.js`.
  - `composables/useMeta.js` (`useHostMeta`, a Vue composable that fetches `/__nuxt_studio/meta`) goes to
    `lib/use-meta.mjs`. It returns the highlight theme that Studio's `server/routes/meta.js:30` sends when no mdc highlight
    theme is configured.

  The sha256 of every Studio file loaded is written to `studioFiles` in the output.
- **The Nuxt Content parse is emulated.** `lib/nuxt-content-parse.mjs` reimplements, from the @nuxt/content 3.16.2 source,
  how a `.md` file becomes the database row that Studio reads. That covers @nuxtjs/mdc `parseMarkdown` with remark-emoji,
  contentHeading, the `.md` link handler, the shiki highlight plugin, minimark compression, the schema/meta split, seo, path
  meta, and SQL value coercion.

The two save paths:

| path | what Studio does | used when |
|------|------------------|-----------|
| D (database) | Nuxt Content row → `sanitizeDocumentTree` → `ensureComarkBody` → `contentFromMarkdownDocument` | Studio opens an existing file (both editors start from it), and for the "Comark formatting available" banner |
| T (text) | `documentFromContent` (comark parse with Studio's options and plugins) → `contentFromMarkdownDocument` | a save after the Monaco code editor changed the text |

An edit made in Studio therefore commits roughly `T(D(x) + edit)`.

## Output format

Top level:

- `documentId`: the content id used for every case (`content/docs/page.md`). When a document has no title, Studio derives
  one from the file name, so the injected title is `Page`.
- `versions`: the package versions that were installed.
- `studioFiles`: sha256 hashes of the Studio files that ran.
- `shiki`: the highlight themes used and how much the shiki plugin changed the tree.
- `summary`: counts over all cases.
- `cases`: one entry per input, in a fixed order: hand-written corpus, then the `.md`/`.mdc` files under `src/test/testData`,
  then the `cases.json` inputs.

Fields of each case. A field that holds markdown is a string. When Studio threw an exception, it is `{"error": "<first line>"}`
instead.

| field | meaning |
|-------|---------|
| `name` | `handwritten/<file>`, `testData/<path>` or `case/<index> <cases.json name>` |
| `source` | `handwritten`, `testData` or `differential` |
| `input` | the file content x. Inputs over 8000 characters would be cut at a blank line, with `truncatedFrom` set; none are now. |
| `collectionType` | `page`, or `data` for corpus files named `*.data.md`. This affects title and description injection. |
| `db` | D(x) |
| `text` | T(x) |
| `textOfDb` | T(D(x)), what a code-editor save without changes would write; `null` when D threw |
| `dbStable` | D(D(x)) == D(x) |
| `textStable` | T(T(x)) == T(x) |
| `db2`, `text2` | D(D(x)) / T(T(x)), present only when the path is not stable |
| `comarkTree` | `{meta, nodes}`, the tree `renderMarkdown` receives on the T path: after the comark parse with Studio's options and `unbindComarkTree`, with `$.line` positions dropped. Nodes are comark arrays `[tag, attrs, ...children]`, and strings are text. |
| `frontmatter` | the front matter object on the T path, i.e. the comark-parsed YAML. The D path's front matter is `cleanDataKeys` of the row and shows only in `db`. |
| `renderedFromTree` | `renderMarkdown({frontmatter, ...comarkTree}, {blockAttributesStyle: 'frontmatter'})`, then `padBlankLinesInComponents`, then `"\n"`. It is computed from the JSON as written, so it shows that the recorded tree is enough to reproduce `text`; `StudioOracleCorpusTest` requires that for every case. |
| `dbTree`, `dbFrontmatter` | the tree and front matter `renderMarkdown` receives on the D path (`comarkTreeFromLegacyDocument` of the database document, then `unbindComarkTree`), or `null` when D threw or Studio found no tree (then `db` is `"\n"`). These trees differ from the T path's: for example `propsMDCToComark` turns `highlights: [2, 4, 5]` into the string `"2 4 5"`, which comark spreads into characters (`{ , ,2,4,5}`). |
| `renderedFromDbTree` | the same rendering of `dbTree`; it must equal `db`. |

Top-level `studioFiles` and `rendererFiles` hold the sha256 of every nuxt-studio, comark and js-yaml file that ran (the
renderer's files are those `comark/render` and comark's `internal/yaml.js` load). Recording stops when they differ from the
published files (pass `--allow-other-studio` or `--allow-other-renderer` to record anyway; the expected hashes live in
`lib/pins.mjs`, shared with `record-db-vectors.mjs`), and `StudioOracleCorpusTest` compares them with its own copy of the
published hashes (`StudioDbVectorsTest` requires `db-vectors.json` to carry the same ones).

Values are encoded by `lib/encode.mjs`. Values that JSON cannot hold are written as
`{"$type": "date" | "number" | "undefined" | "bigint", "value": ...}`, for example YAML timestamps (`Date`) and `.nan` or
`.inf`. An array or object that is reached more than once from one encoded root (what YAML anchors and aliases load as; js-yaml
dumps it as `&ref_N` / `*ref_N`) is written in full once as `{"$type": "shared", "id": N, "value": ...}` and elsewhere as
`{"$type": "ref", "id": N}`. `frontmatter`, `meta` and `nodes` are separate roots. Key order matches the JavaScript object, so
integer-like keys come first, as the renderer sees them.

## Fidelity caveats

- @nuxt/content itself is not installed; its parse is reimplemented. The reimplementation assumes:
  - no user remark or rehype plugins;
  - no `content.build.markdown` options;
  - the default path meta;
  - the standard page schema (`title`, `description`, `path`, `seo`, `body`, `navigation`) or the bare data schema, plus
    optional user fields given as `opts.schema` (`{ name, type, default }`, with the SQL conversion of their column type;
    used by `record-db-vectors.mjs`; `record.mjs` uses none);
  - `collection = null` in `sanitizeDocumentTree`, so keys hidden in the editor are not stripped.

  The SQLite round trip is emulated as a JSON round trip, with VARCHAR columns passed through `String()`.
- The TipTap visual-editor conversion (minified in `dist/app/main.js`) is not run. A visual-editor save also passes the D
  body through TipTap.
- Shiki: Studio passes the theme names `github-light` and `github-dark` to comark's shiki plugin. That plugin only registers
  loaders for the material themes, so highlighting throws, and the plugin's catch branch only sets `class: "shiki"` on
  `<pre>`. `renderMarkdown` ignores that class, so shiki has no effect on the output. Studio's browser bundle uses the same
  comark code, so it should behave the same, but this has not been checked in a browser. The D path's emulated Nuxt Content
  highlight (material-theme-palenight) only adds styles that Studio strips.
- `renderMarkdown` and the parse run in Node, not in a browser. Neither depends on the platform.
