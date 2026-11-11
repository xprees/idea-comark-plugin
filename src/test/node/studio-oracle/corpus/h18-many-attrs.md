---
title: Attrs
---

::card{title="T" description="D" icon="i-x" to="/y" target="_blank"}
Four plus attrs
::

::card{a="1" b="2" c="3"}
Exactly three
::

::card{.one.two #id a="1" b="2"}
Class id plus two
::

::card{:items='["a","b",{"c":1}]' :config='{"nested":{"deep":true}}' flag}
Object and array props
::

::card{title='single "quoted"' other="double 'quoted'" plain=unquoted}
Quote styles
::

::card{empty="" bare}
Empty and bare
::
