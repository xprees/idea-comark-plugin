---
title: Scalars
string: plain string
quotedColon: "a: b"
singleQuoted: 'it''s'
int: 42
negative: -7
float: 3.14
exp: 1e3
octalLike: 0755
hex: 0x1F
leadingZero: '007'
versionLike: 1.10
boolTrue: true
boolYes: yes
boolNo: no
on: on
nullTilde: ~
nullWord: null
empty:
date: 2024-02-29
datetime: 2024-02-29T12:30:00Z
datetimeOffset: 2024-02-29 12:30:00 +02:00
emptyString: ''
emptyArray: []
emptyObject: {}
inlineArray: [1, "two", three]
inlineObject: {a: 1, b: two}
literal: |
  keep
  newlines
folded: >
  fold
  these
keepTrailing: |+
  trailing

stripTrailing: |-
  stripped
unicode: "日本語 ✓"
emoji: "😀"
url: https://example.com/path?q=1
hashInValue: value # comment
longString: "This is a long string value that exceeds the typical eighty character line width used by YAML dumpers by default"
---

Body.
