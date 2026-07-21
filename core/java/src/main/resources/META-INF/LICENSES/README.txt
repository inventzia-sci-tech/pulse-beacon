License map for the pulse-beacon runtime bundle
================================================

This directory preserves the license texts needed for the libraries embedded
in the shaded runtime jar. META-INF/THIRD-PARTY.txt records the exact artifact
versions included by the build.

Inventzia components
--------------------

* pulse-beacon and pulse-data: LICENSE-AGPL-3.0 or
  LICENSE-COMMERCIAL.txt, at the licensee's option.

Bundled third-party components
------------------------------

* Jackson annotations, core, databind and JSR-310 modules: Apache-2.0.txt.
* JSpecify annotations: Apache-2.0.txt.
* SLF4J API: MIT-SLF4J.txt.
* Logback classic and core: LGPL-2.1.txt. Logback is dual-licensed under EPL
  1.0 or LGPL 2.1; this distribution selects the LGPL 2.1 option.
* FastDoubleParser, embedded by Jackson Core: the upstream
  META-INF/FastDoubleParser-LICENSE and META-INF/FastDoubleParser-NOTICE
  resources are preserved separately in this jar.

The aggregated META-INF/NOTICE retains notices supplied by dependencies.
