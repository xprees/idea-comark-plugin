# Contributing to Comark for JetBrains IDEs

Thanks for helping. Bug reports, fixes, new features, tests and documentation are all welcome. This guide covers how to get
the project running, how the code is organized, and what a pull request needs before it can be merged.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report security problems privately, as
described in [SECURITY.md](SECURITY.md), not in a public issue.

## Contents

- [Before you start](#before-you-start)
- [Setting up](#setting-up)
- [Project layout](#project-layout)
- [Building and running](#building-and-running)
- [Tests](#tests)
- [Coding guidelines](#coding-guidelines)
- [IntelliJ Platform rules](#intellij-platform-rules)
- [Commits, changelog and pull requests](#commits-changelog-and-pull-requests)
- [Releases (maintainers)](#releases-maintainers)
- [Licensing](#licensing)

## Before you start

- **Small fixes** (a typo, an obvious bug with a test) can go straight to a pull request.
- **Bigger changes** (a new feature, a new setting, a change to how files are parsed or formatted): open an issue first and
  describe the problem and the approach. It saves you from building something that conflicts with work in progress or with
  the project's goals.
- **Compatibility is the point of this plugin.** Behaviour follows the reference implementations: the comark parser, Nuxt
  Content (remark-mdc), the VS Code extension's formatter (`@nuxtlabs/monarch-mdc`) and Nuxt Studio. When you change how a
  document is read or written, say which reference you checked and how (a link to the source, a test against recorded output).
- For a starting point, look for issues labelled `good first issue` or `help wanted`, or ask in an issue.

## Setting up

You need:

- **JDK 21** (any distribution; Gradle's toolchain support picks it up).
- **IntelliJ IDEA** (Community is enough) for development, with the *Plugin DevKit* plugin.
- **Node.js 20.6 or newer**, only if you re-record the Nuxt Studio oracle (see [Tests](#tests)).

```shell
git clone https://github.com/xprees/idea-comark-plugin.git
cd idea-comark-plugin
./gradlew build
```

Open the directory in IntelliJ IDEA as a Gradle project. The shared run configurations in `.run/` (*Run Plugin*, *Run Tests*,
*Run Verifications*) appear in the toolbar.

The first build downloads the IntelliJ Platform (about 1.5 GB) into the Gradle cache.

## Project layout

```
src/main/kotlin/cz/xprees/comark/
  lang/            the Comark dialect of the Markdown language, file types, the language substitutor
  parser/          the block structure parser (components, props, slots) and the inline rewriter
  psi/             PSI elements for components, props, slots and attributes
  highlighting/    lexer-level and annotator highlighting, color settings
  editor/          folding, brace matching, typing handlers, live templates, structure view
  completion/      component, prop, slot and attribute completion
  meta/            component metadata: Vue components, nuxt-component-meta, naming rules
  navigation/      Go to Declaration, Find Usages, Rename
  images/          Nuxt image paths: references, completion, inspection, documentation
  format/          Reformat Code: the VS Code style (monarch-mdc port) and the Nuxt Studio style
  format/studio/   the Nuxt Studio engine: Kotlin ports of Studio's two save paths, platform independent
  preview/         components in the Markdown preview
  settings/        Settings › Languages & Frameworks › Comark
  compat/          workarounds for Markdown plugin features that check for plain Markdown
src/main/resources/
  META-INF/plugin.xml          extension registrations
  messages/ComarkBundle.properties   every user-visible string
src/test/kotlin/   tests, mirroring the main packages
src/test/testData/ fixtures and recorded reference output (see below)
src/test/node/studio-oracle/   Node scripts that record Nuxt Studio's real output
```

The README's [How it works](README.md#how-it-works) section explains the parser design.

## Building and running

```shell
./gradlew runIde          # a sandbox IDE with the plugin installed
./gradlew test            # all tests (10 to 20 minutes)
./gradlew test --tests '*ProseComponentNamesTest'   # one test class while you iterate
./gradlew buildPlugin     # build/distributions/comark-<version>.zip
./gradlew verifyPlugin    # JetBrains Plugin Verifier against IDEA, WebStorm and Rider
```

`runIde` starts the oldest supported IDE (`platformVersion` in `gradle.properties`). Try your change there in a small Nuxt
Content project: a `package.json` that depends on `@nuxt/content` and a `content/` directory is enough for `.md` files to
become Comark.

## Tests

Every change in behaviour needs a test that fails without it. Most tests extend `BasePlatformTestCase` and work on files
created with `myFixture.configureByText`; look at a neighbouring test in the same package and follow it.

- **Run the full suite once before you open a pull request.** While you work, run the classes you touch with `--tests`.
- **Assert exact results.** Compare the whole formatted text, the full list of completion items, the exact highlighted ranges.
  A test that only checks "something changed" or "no exception" lets regressions through.
- **Don't change expected values to make a test pass** unless the new value is what the reference implementation produces.
  Say so in the pull request.
- **Don't skip, disable or loosen a failing test.** Find the cause.

### Recorded reference data

Several tests compare the plugin with the real reference implementations through data recorded from them. The files are
committed, so the Kotlin tests never need Node:

| Data | Recorded from | Re-record with |
|---|---|---|
| `src/test/testData/formatter/upstream/` | monarch-mdc's own test suite (MIT, see the LICENSE there) | copy from the monarch-mdc repository |
| `src/test/testData/formatter/differential/cases.json` | `@nuxtlabs/monarch-mdc` 0.10.0 run under Node | add cases only with output produced by monarch-mdc itself |
| `src/test/testData/studio/*.json` | nuxt-studio 1.8.0, comark 0.7.0, Nuxt Content | `./gradlew recordStudioOracle` and the other `recordStudio*` tasks |

The [oracle README](src/test/node/studio-oracle/README.md) explains each Studio data set and its recorder. Some rules apply to
all of them:

- **Never edit recorded data by hand.** Change the recorder and record again. Recordings are deterministic: recording twice
  gives identical bytes, and a diff in a pull request should show only the cases you meant to change.
- **Pinned counts are real measurements.** Tests such as `StudioStyleOracleTest` pin how many documents fall into each
  category. When your change moves a count, explain why in the pull request. A count that gets worse needs a reason the
  reviewer can accept.
- **Versions are pinned.** The recorders check the hashes of the installed packages. Moving to a new nuxt-studio or comark
  version is a change of its own: re-record everything, then fix what the new version broke.

## Coding guidelines

The code follows the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html), set up in
`.editorconfig`: 4-space indentation and lines up to 140 columns. Use *Code › Reformat Code* in IntelliJ IDEA before you commit.

- **Write KDoc for every non-private declaration.** Say what it is for and the cases that aren't obvious (what `null` means,
  which reference behaviour it copies). Most classes here document the external behaviour they port; keep that up to date.
- **Name things after the domain.** Use the terms the comark, Nuxt Content and Studio sources use (block component, props
  block, slot, text path, database path), so a reader can find the counterpart.
- **Prefer small, pure functions** over state. The formatting engines (`format/studio/`, the monarch-mdc port) don't depend on
  the IntelliJ Platform. Keep it that way, so they stay testable without a fixture and reusable.
- **Ported code stays close to its source.** When you port from JavaScript, keep the structure and names recognisable, cite
  the file (and version) in a comment, and list it in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **No dead code or commented-out code.** Remove it; git keeps the history.
- **User-visible strings go in `ComarkBundle.properties`** and are read with `ComarkBundle.message(...)`. Write them in sentence
  case, following the [IntelliJ Platform UI guidelines](https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html).
- **Settings need a reason.** Add an option only when users really disagree about the behaviour; otherwise pick the behaviour
  of the reference implementation.
- **Mind performance.** Inspections, annotators, completion and the parser run while the user types. Avoid work that is
  quadratic in the file size, cache per file modification where it helps, and check large inputs (the tests include
  documents with thousands of lines).

## IntelliJ Platform rules

The plugin supports every IDE from `platformVersion` (2026.1) on, with no upper build limit, and installs without a restart.
Pull requests are checked against these rules by CI:

- **Use only public, stable APIs.** The Plugin Verifier fails the build on internal API use (`@ApiStatus.Internal`) and warns on
  deprecated or experimental API. If you have no alternative to an experimental API (some Markdown plugin APIs are
  experimental), say so in the pull request.
- **Compile against the oldest supported platform.** The build already does this, so an API that is missing in 2026.1 is a
  compile error, not a runtime crash. Don't raise `platformVersion` to get a newer API without discussing it first.
- **Stay a dynamic plugin.** Register everything in `plugin.xml` (no static registration), keep no state in top-level or
  companion-object singletons that outlives the plugin, use light services (`@Service`), and give listeners and other
  resources a parent `Disposable`. `verifyPlugin` reports whether the plugin can still be loaded and unloaded without a
  restart.
- **Respect threading rules.** Read PSI in a read action and write in a write action. Don't block the EDT with I/O or long
  computations. Use `ReadAction.computeBlocking`, `ReadAction.nonBlocking` or coroutines; avoid deprecated helpers.
- **Don't break plain Markdown.** Comark is a dialect of the bundled Markdown language. Everything the plugin adds must stay off
  in ordinary `.md` files that aren't Comark.

Run `./gradlew verifyPlugin` locally when you touch platform APIs. CI verifies only the newest IDEA, WebStorm and Rider by
default. Add the `full-verify` label to a pull request to verify the whole version matrix (from its next push on).

## Commits, changelog and pull requests

### Commits

- Write the subject in the imperative and sentence case, without a trailing period: *Accept Prose components without the
  prose- prefix*. Keep it under about 72 characters.
- Use the body to explain why, and anything a reviewer would otherwise have to work out from the diff.
- Keep each commit focused. Don't mix refactoring, formatting and behaviour changes in one commit.

### Changelog

Every user-visible change gets an entry under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) sections *Added*, *Changed*, *Fixed* or *Removed*. Write it for
users: what they can now do or what no longer goes wrong, not which class changed. The release workflow turns these entries
into the Marketplace release notes. Internal changes (tests, CI, refactoring) need no entry.

### Pull requests

1. Fork the repository (or create a branch, if you have write access) and branch from `master`.
2. Make the change, with tests and a changelog entry.
3. Run `./gradlew test` (the full suite) and, if you touched platform APIs, `./gradlew verifyPlugin`.
4. Open the pull request and fill in the template: what changed, why, and how you tested it.
5. CI must be green: *Build*, *Test* and *Verify plugin*. Fix failures in the pull request. "Flaky" is not a cause; find out
   why a test failed.
6. To resolve conflicts, merge `master` into your branch. Don't force-push a branch someone else is reviewing; push new commits
   instead. The maintainer may squash the pull request when merging it.

One feature or fix per pull request. Large features can be split into a series of pull requests that each build and pass the
tests on their own.

### AI-assisted contributions

Generated code is welcome under the same rules as any other code: you are responsible for it, you have read and understood it,
and it comes with tests. Say in the pull request if a substantial part was generated.

## Releases (maintainers)

Every merge to `master` that passes CI is tagged with the next version, and a draft GitHub release is created with the built
plugin attached. The README's [Versions and releases](README.md#versions-and-releases) section has the details.

To ship:

1. Check the draft release notes. They come from `## [Unreleased]` in the changelog.
2. Publish the draft. The *Release* workflow signs the build and publishes it to the JetBrains Marketplace.
3. **Merge the `Changelog update - <version>` pull request** the workflow then opens. It moves the released entries under the
   new version heading. Until it is merged, the next release's notes repeat this release's entries.

## Licensing

The project is licensed under [Apache-2.0](LICENSE). By contributing, you agree that your contribution is licensed under the
same terms (Apache-2.0, section 5). Only contribute code you have the right to submit. Code ported or adapted from other
projects must have a compatible license and be listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
