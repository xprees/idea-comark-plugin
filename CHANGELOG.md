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
- Comark logo as the plugin icon and as the file icon of `.mdc` files
- Nuxt Content route links (`/guide/install#setup`) resolve to their content files: Go to Declaration, anchors in the target page
  and route completion

### Fixed

- Code fences indented inside components no longer log errors from Markdown's fence language injection
- Enter inside a component nested in a list item no longer inserts a list bullet into the component body
- Code › Auto-Indent Lines (and other indentation adjustments, such as indenting pasted text) use the Comark formatter's
  indentation instead of flattening nested components
- Reformat Code keeps components inside list items in the list item, and no longer changes Markdown content: tabs in code fences,
  indented code blocks and hard line breaks are kept. These are deliberate differences from the VS Code extension's formatter
- Enter after a block quote line inside a component continues the block quote
- Components inside list items can be folded
- The Comark code style page no longer offers "Use tab character" and tab size, which the formatter ignores (it always indents
  with spaces)
