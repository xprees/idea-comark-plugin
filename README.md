# Comark

<!-- Plugin description -->
Support for <b>Comark</b> (Components in Markdown, formerly MDC) as used by <b>Nuxt Content</b>, in WebStorm, IntelliJ IDEA,
Rider and other JetBrains IDEs.

<ul>
  <li><b>Highlighting</b> of block components (<code>::alert{type="info"}</code> … <code>::</code>), nested components (<code>:::</code>),
      YAML props blocks, named slots (<code>#footer</code>), inline components (<code>:badge[New]{color="blue"}</code>), spans and attributes</li>
  <li><b>Reformat Code</b> with a port of the VS Code extension's formatter, and indentation on Enter that matches it</li>
  <li><b>Component completion</b>, <b>Go to Declaration</b> and <b>documentation</b> for components, props and slots, from your
      <code>components/</code> Vue files and nuxt-component-meta output</li>
  <li>Folding, matching <code>::</code> pairs, YAML editing in props, live templates, auto-closing of components, breadcrumbs and a
      structure view; components rendered in the Markdown preview</li>
</ul>

The bundled Markdown plugin keeps working for Comark files. <code>.mdc</code> files are always Comark; <code>.md</code> files are Comark
when they belong to a package that depends on Nuxt Content, Nuxt MDC or Comark (configurable in
<i>Settings › Languages &amp; Frameworks › Comark</i>).
<!-- Plugin description end -->

Works in JetBrains IDEs based on the IntelliJ Platform **2026.1 and newer** that bundle the Markdown plugin (IntelliJ IDEA, WebStorm,
Rider, PyCharm, …). The plugin is built and tested against the oldest supported IDE (`platformVersion` in `gradle.properties`, which also
gives its `since-build`) and verified against the latest IDEA, WebStorm and Rider releases in CI (the full version matrix is opt-in: the
`full-verify` pull request label or a manual run of the *Build* workflow). It declares no upper build limit.

## Which files are Comark

| File | Comark when |
|---|---|
| `*.mdc` | always |
| `*.md` | *Auto* (default): a `package.json` in the file's directory or above depends on `@nuxt/content`, `@nuxtjs/mdc`, `comark`, `@comark/*`, `docus` or `@nuxt/ui-pro`; *All .md files*; or never |

The setting lives in *Settings › Languages & Frameworks › Comark*. Comark files keep the Markdown file type, so everything the
Markdown plugin offers (preview, structure view, lists, tables, header anchors, code fence injection, …) still applies.

## Features

| Area | What you get |
|---|---|
| Highlighting | Component markers, names, closers, attributes (`#id`, `.class`, `key="value"`, `:binding`), slots, props fences; inline components, spans, attribute groups and `{{ bindings }}` in text. Colours in *Settings › Editor › Color Scheme › Comark* |
| Inspections | *Unclosed block component* (with a fix inserting the closer), *Ignored text after component opener*, *Unknown Comark component* (off by default). Markdown's unresolved-link and unresolved-anchor inspections understand Comark spans and `{#id}` anchors |
| Formatting | *Code › Reformat Code* uses a line-by-line port of the VS Code extension's formatter (monarch-mdc), checked byte for byte against the original on ~59,000 cases. Indent size: *Settings › Editor › Code Style › Comark* (2 by default; EditorConfig wins). Enter indents new lines the same way |
| Components | Completion of component names (`::`, `:::`, inline `:`), props inside `{…}` and in props YAML, prop values from string unions, slot names after `#`; Go to Declaration to the `.vue` file, prop and `<slot>`; quick documentation. Sources: Vue SFCs in `components/` (Nuxt naming), `.nuxt/component-meta.mjs` / `.json`, an optional metadata URL. No Vue plugin needed |
| Editor | Folding of components, slots and props (Markdown folds kept); matching `::name` / `::` highlighting and navigation; YAML highlighting and checks in props blocks; live templates from the VS Code snippets; Enter after an unclosed opener inserts its closer; breadcrumbs and sticky lines; structure view with headers, components and slots; spellchecking of component content and attribute values |
| Preview | Components, props and slots render as labelled blocks in the built-in (JCEF) Markdown preview |

## Settings

*Settings › Languages & Frameworks › Comark*: which `.md` files are Comark, whether Reformat Code uses the Comark formatter (otherwise
the Markdown one), component completion, an optional component metadata URL and its cache time.
*Settings › Editor › Code Style › Comark*: indentation used by the formatter and Enter.

## Known limitations

- Some Markdown plugin features check for the plain Markdown language and stay off in Comark files: *Create Link*, *Insert Image*,
  table column/row actions and image drag and drop. HTML `<a id>` anchors are not link targets.
- Reformat Code re-aligns top-level tables only; tables inside components are left as they are.
- The preview renders components only with the built-in JCEF preview; Markdown settings list "JCEF Browser" twice (both entries are
  the same choice), a side effect of how the plugin hooks the preview.
- Prop types imported from other files (`defineProps<Props>()` with an imported `Props`) are not resolved; components inside
  `node_modules` are known only through nuxt-component-meta output or the metadata URL.

## How it works

Comark is registered as a dialect of the bundled Markdown language and Markdown files are switched to it by a `LanguageSubstitutor`
(`lang/`). A Comark file is still a `MarkdownFile`, so Markdown features keep working.

The parser (`parser/ComarkTreeBuilder.kt`) finds the Comark block structure line by line, following the reference parser
([comark `components.ts`](https://github.com/comarkdown/comark)): component openers and closers (matching colon counts, code fences
and same-depth nesting), `---` props blocks right after an opener and `#slot` lines. The Markdown between those lines is parsed by the
Markdown plugin's parser with the component's own indentation removed, exactly as Comark does (so indented content inside a component
is a paragraph, not a code block), and mapped back into one tree. The lexer and PSI parser are built from that tree the same way the
Markdown plugin builds its own.

## Building

```shell
./gradlew test          # parser and feature tests
./gradlew runIde        # sandbox IDE with the plugin
./gradlew buildPlugin   # build/distributions/comark-<version>.zip
./gradlew verifyPlugin  # JetBrains Plugin Verifier against IDEA, WebStorm and Rider
```

Requires JDK 21.

### Versions and releases

The plugin version is `major.minor.patch`, derived from git tags:

- **Tagged commit:** the tag is the version (`0.1.3`, `v0.1.3`, or `0.2.0-beta.1`, whose suffix selects the Marketplace channel: no suffix = stable, `-beta.N` = beta).
- **Any other build:** `major.minor` come from `pluginVersionMajor` / `pluginVersionMinor` in `gradle.properties` (the `PLUGIN_VERSION_MAJOR` / `PLUGIN_VERSION_MINOR` environment variables override them; fallback `0.1`), and the patch is the next free one: the highest existing `major.minor.N` tag plus one, or `0` if there is none.
- `-PpluginVersion=x.y.z` overrides everything.

**On every merge to `master`**, once build, tests and plugin verification are green, the *Build* workflow tags the merge commit with the next version and creates a draft GitHub release for that tag. Major and minor come from the repository variables `PLUGIN_VERSION_MAJOR` and `PLUGIN_VERSION_MINOR` (*Settings → Secrets and variables → Actions → Variables*); if they are not defined, the values in `gradle.properties` are used. The patch increases automatically.

The draft release already has the built plugin attached: `comark-<version>.zip`, plus `comark-<version>-signed.zip` when the signing secrets (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) are configured. That zip is also what you upload by hand for the very first Marketplace release.

To start a new line (say `0.2`), change the two variables; the next merge is tagged `0.2.0`. To ship a build, publish its draft release: the *Release* workflow then builds that exact tag, signs it and publishes it to the Marketplace (`PUBLISH_TOKEN` secret). Signing and publishing are refused for builds that aren't from a tag. For a beta, edit the draft's tag before publishing (e.g. `0.2.0-beta.1`).

## License

Apache-2.0, see [LICENSE](LICENSE). Code ported from other projects is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
