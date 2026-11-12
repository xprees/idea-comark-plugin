# Third-party notices

This plugin contains code ported from the following MIT-licensed projects.

## @nuxtlabs/monarch-mdc

The Comark formatter (`cz.xprees.comark.format`) is a Kotlin port of the formatter in
[@nuxtlabs/monarch-mdc](https://github.com/nuxtlabs/monarch-mdc) 0.10.0, which the VS Code extension uses (Comark VS Code extension
0.6.2 ships monarch-mdc 0.9.0, whose formatter behaves identically).

```
MIT License

Copyright (c) 2021 NuxtLabs

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.```

## vscode-comark

The live templates are adapted from the snippets of the
[Comark VS Code extension](https://github.com/comarkdown/vscode-comark), and its behaviour (component metadata
formats, completion contexts) served as the reference for this plugin.

```
MIT License

Copyright (c) 2016-2022 Comark team and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Comark

The block parser follows the reference implementation in [Comark](https://github.com/comarkdown/comark)
(`packages/comark/src/plugins/components.ts`, `internal/parse/syntax/props.ts`).
The plugin icon (`META-INF/pluginIcon.svg`, `pluginIcon_dark.svg`) is the Comark logo from `docs/public/logo-light.svg` and
`docs/public/logo-dark.svg`, scaled to 40×40.

```
MIT License

Copyright (c) 2026 Comark team and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Nuxt Studio formatting style

`cz.xprees.comark.format.studio` reproduces what [Nuxt Studio](https://github.com/nuxt-content/nuxt-studio) 1.8.0 writes when
it saves a document. It contains Kotlin ports of parts of these projects (all MIT-licensed unless noted):

- Nuxt Studio 1.8.0 (`utils/document/legacy.js`, `generate.js`): Copyright (c) 2025 Vercel, Inc.
- Comark 0.7.0 (parser plugins, token processing, `renderMarkdown`, emoji table): Copyright (c) 2026 Comark team and
  contributors.
- markdown-exit 1.1.0-beta.2, a fork of markdown-it (block and inline parser): Copyright (c) 2014 Vitaly Puzrin, Alex
  Kocharin; Copyright (c) 2025 SerKo.
- mdurl 2 (URL parse, format, encode, decode): Copyright (c) 2015 Vitaly Puzrin, Alex Kocharin.
- js-yaml 5.5.0 (loader and dumper): Copyright (C) 2011-2015 by Vitaly Puzrin.
- htmlparser2 12 (tokenizer and parser): Copyright 2010, 2011, Chris Winberry.
- punycode.js 2: Copyright Mathias Bynens.
- character-entities 2 and character-entities-legacy 3 (named character reference tables): Copyright (c) 2015 Titus Wormer.
- entities 7/8 (character reference decoding rules), BSD-2-Clause: Copyright (c) Felix Böhm.

The MIT License text above applies to each MIT-licensed project with its own copyright line. The entities license:

```
Copyright (c) Felix Böhm
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the distribution.

THIS IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Test tooling (not shipped)

The Nuxt Studio oracle recorders under `src/test/node/studio-oracle` contain code derived from these MIT-licensed projects;
they only run to record test data:

- @nuxt/content 3.16.2 (`lib/nuxt-content-parse.mjs`: the markdown parse options, path meta, schema split, SQL value
  conversion and `formatDate`/`formatDateTime`; `lib/nuxt-content-runtime.mjs`): Copyright (c) Nuxt Project.
- Comark 0.7.0 (`lib/db-diff.mjs`: `applyAutoUnwrap`): Copyright (c) 2026 Comark team and contributors.
