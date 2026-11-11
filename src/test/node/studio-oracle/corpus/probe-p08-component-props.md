---
title: T
---

::card{title="Hello" :count="3" :flag="true" .red #main icon=i-lucide-x}
Body
::

::card
---
title: Props block
items:
  - a
  - b
nested:
  key: value
---
Body
::

::card
```yaml [props]
title: Yaml fence
```
Body
::
