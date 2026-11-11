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

`record-vectors.mjs` (Gradle: `recordStudioVectors`) records two smaller files next to `oracle.json` for the Kotlin port of
the renderer:

- `yaml-vectors.json`: js-yaml 5.5.0 `dump(value, { indent: 2, lineWidth: -1 })` and comark's `stringifyYaml(value)` for
  about 2000 values (numeric-looking strings, YAML 1.1 booleans and nulls, dates, indicators, multi-line strings, unicode,
  numbers, empty and nested collections), each at the root, as a mapping value, as a sequence item and, for strings, as a key.
  `StudioYamlTest` checks `StudioYaml` against it.
- `render-vectors.json`: comark `renderMarkdown(tree, { blockAttributesStyle: 'frontmatter' })` for hand-made trees with
  attribute values the parsed corpus does not contain (numbers, booleans, `null`, objects, bindings, `$` html markers, odd
  `ol` starts). `StudioRenderVectorsTest` checks `StudioRenderer` against it.

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
| `renderedFromTree` | `renderMarkdown({frontmatter, ...comarkTree}, {blockAttributesStyle: 'frontmatter'})`, then `padBlankLinesInComponents`, then `"\n"`. It is computed from the JSON as written, so it shows that the recorded tree is enough to reproduce `text`. |

Values that JSON cannot hold are written as `{"$type": "date" | "number" | "undefined" | "bigint", "value": ...}`, for
example YAML timestamps (`Date`) and `.nan` or `.inf`. Key order matches the JavaScript object, so integer-like keys come
first, as the renderer sees them.

## Fidelity caveats

- @nuxt/content itself is not installed; its parse is reimplemented. The reimplementation assumes:
  - no user remark or rehype plugins;
  - no `content.build.markdown` options;
  - the default path meta;
  - the standard page schema (`title`, `description`, `path`, `seo`, `body`, `navigation`) or the bare data schema, with no
    custom schema fields;
  - `collection = null` in `sanitizeDocumentTree`, so keys hidden in the editor are not stripped.

  The SQLite round trip is emulated as a JSON round trip, with VARCHAR columns passed through `String()`.
- The TipTap visual-editor conversion (minified in `dist/app/main.js`) is not run. A visual-editor save also passes the D
  body through TipTap.
- Shiki: Studio passes the theme names `github-light` and `github-dark` to comark's shiki plugin. That plugin only registers
  loaders for the material themes, so highlighting throws, and the plugin's catch branch only sets `class: "shiki"` on
  `<pre>`. `renderMarkdown` ignores that class, so shiki has no effect on the output. Studio's browser bundle uses the same
  comark code, so it should behave the same, but this has not been checked in a browser. The D path's emulated Nuxt Content
  highlight (material-theme-palenight) only adds styles that Studio strips.
- YAML anchors and aliases: the JSON tree cannot express that two values are the same object, while js-yaml's dump writes
  shared objects as `&ref_0` / `*ref_0`. For such documents `renderedFromTree` differs from `text`. There is currently one:
  `handwritten/h26-frontmatter-structures.md`.
- `renderMarkdown` and the parse run in Node, not in a browser. Neither depends on the platform.
