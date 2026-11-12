// Derived from @nuxt/content 3.16.2 (MIT, Copyright (c) Nuxt Project); used only to record test data.
// Stand-in for '@nuxt/content/runtime' as imported by nuxt-studio 1.8.0 utils/document/legacy.js:1.
// Verbatim copy of the functions legacy.js uses from @nuxt/content 3.16.2 dist/runtime/index.js
// (@nuxt/content itself is not installed: it pulls in Nuxt, a SQLite driver and the module builder).
import { toHast, fromHast } from 'minimark/hast'
import { visit as minimarkVisit } from 'minimark'
export function compressTree(input) {
  return fromHast(input)
}
export function decompressTree(input) {
  return toHast({ type: 'minimark', value: input.value })
}
export function visit(tree, checker, visitor) {
  minimarkVisit({ type: 'minimark', value: tree.value }, checker, visitor)
}
