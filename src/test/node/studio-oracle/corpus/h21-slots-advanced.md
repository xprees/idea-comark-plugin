---
title: Slots
---

::tabs
#first{label="First" icon="i-a"}
First content

  ::nested
  inside slot
  ::

#second
- list in slot
- second

#third
```ts
code in slot
```
::

::card
#default
Explicit default only
::

::card
Implicit default
#footer
:button{label="Go"}
::
