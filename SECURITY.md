# Security policy

## Supported versions

Only the latest release on the JetBrains Marketplace gets security fixes. Update the plugin
(*Settings › Plugins › Installed › Comark › Update*) before you report a problem.

## Reporting a vulnerability

**Don't open a public issue for a security problem.** Report it privately through GitHub:

1. Open the repository's [Security tab](https://github.com/xprees/idea-comark-plugin/security).
2. Choose **Report a vulnerability** and fill in the form.

Include what you can of:

- the plugin version and the IDE (name, version, operating system);
- what an attacker can do, and under which conditions (for example: opening a crafted Markdown file, a malicious
  `content.config.ts` or `package.json` in a cloned project, rendering a document in the preview);
- steps or a minimal file that reproduce it.

You can expect an acknowledgement within a week. Once the problem is confirmed, a fix is released as soon as possible and the
advisory is published with the release. You are credited in the advisory unless you prefer not to be.

## Scope

The plugin reads files of the open project (Markdown, Vue components, `package.json`, lockfiles, `content.config.ts`,
`nuxt.config.ts`), renders Markdown in the IDE's preview, and can fetch component metadata from a URL you configure. Problems
in these areas are in scope, for example:

- code or script execution, or network access, triggered by opening or previewing a file;
- reading files outside the project, or leaking their content;
- the IDE hanging or running out of memory on a small crafted file (report these as security problems only when one file is
  enough to make the IDE unusable; slow formatting of a huge file is a normal bug).

Vulnerabilities in the IntelliJ Platform, the bundled Markdown plugin or JCEF belong to
[JetBrains](https://www.jetbrains.com/privacy-security/issues-fixed/) instead.
