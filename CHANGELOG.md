# Changelog

## [Unreleased]

### Added

- Comark language: `.mdc` files, and `.md` files in Nuxt Content / Nuxt MDC / Comark packages, are parsed as Comark on top of the
  bundled Markdown plugin (block components, nested components, YAML props blocks, slots, attributes)
- Settings page: which Markdown files are Comark, formatter and component completion options
- Syntax highlighting, inline Comark highlighting, colour settings and syntax inspections
- Formatter (port of the VS Code extension's) for Reformat Code, with matching indentation on Enter
- Folding, `::` pair matching, YAML in props blocks, live templates, auto-closing, breadcrumbs, structure view
- Component completion, navigation and documentation from Vue components and nuxt-component-meta
- Markdown plugin compatibility (HTML blocks, TODOs, Fill Paragraph) and components in the Markdown preview
- Code style page for Comark (indent 2 by default) and spellchecking of component content and attribute values
- Comark logo as the plugin icon and as the file icon of Comark files (`.mdc`, and `.md` files treated as Comark)
- Exclusion patterns in the settings: globs of `.md` files and directories that are never treated as Comark
- Component props from imported types: `defineProps<Props>()` with `Props` imported from a TypeScript file (relative paths and
  the Nuxt `~/`, `@/`, `~~/`, `@@/` aliases, `index.ts` / `.d.ts`, re-exports, `extends` and `A & B` of imported types); editing
  the imported file updates completion, and Go to Declaration opens it at the prop
- Find Usages and Rename for components: component names in Comark files reference their `.vue` file, Find Usages on the file
  (or on a usage) lists block, nested and inline usages, and renaming the file updates them in the style they were written in
- Nuxt Content route links (`/guide/install#setup`) resolve to their content files: Go to Declaration, anchors in the target page
  and route completion

### Fixed

- Code fences indented inside components no longer log errors from Markdown's fence language injection
- Preview: components inside list items render as components instead of raw `::` text
- Preview: large documents with many link reference definitions render quickly again (references resolved once, not per block)
- Preview: repeated headings get unique ids across components (`usage`, `usage-1`), so in-page anchors reach every heading
- Preview: link reference definitions no longer show up inside an unclosed code fence or HTML block, with correct scroll positions
- `.nuxt/component-meta.mjs` written by current nuxt-component-meta versions (a deduplicated module with string tables and
  shared objects) is read; before, its components (module components such as Nuxt UI / Docus prose components) were missing
- A half-typed `defineModel('` in a component no longer breaks completion, documentation and navigation in all Comark files, and
  a component the parser cannot read is skipped instead of failing the whole component index
- Components in subfolders of `components/content/` are named as Nuxt Content registers them (`content/prose/Card.vue` is
  `card`, not `prose-card`) in completion, documentation, the unknown-component inspection, Find Usages and Rename
- Nuxt component naming: grouping folders (`components/(forms)/`) are not part of names, `components/islands/` is a component
  directory and `.island` files drop the suffix
- The unknown-component inspection no longer reports HTML elements used as components (`::div`, `:br`, `::table`)
- Editing a `.vue` file no longer makes the next completion slow in projects with many components and large component metadata
- Props typed with a type alias of the component (`type Variant = 'solid' | 'outline'`, also imported) get value completion
  and their kind from the aliased type
- Enter inside a component nested in a list item no longer inserts a list bullet into the component body
- Reformat Code and Auto-Indent Lines honour *Code Style › Formatter › Do not format* entries and formatter markers
  (`<!-- @formatter:off -->` … `<!-- @formatter:on -->`, or `[//]: # (@formatter:off)` comments, custom and regular expression tags)
- Code › Auto-Indent Lines (and other indentation adjustments, such as indenting pasted text) use the Comark formatter's
  indentation instead of flattening nested components
- Reformat Code keeps components inside list items in the list item, and no longer changes Markdown content: tabs in code fences,
  indented code blocks and hard line breaks are kept. These are deliberate differences from the VS Code extension's formatter
- Enter after a block quote line inside a component continues the block quote
- Components inside list items can be folded
- The Comark code style page no longer offers "Use tab character" and tab size, which the formatter ignores (it always indents
  with spaces)
