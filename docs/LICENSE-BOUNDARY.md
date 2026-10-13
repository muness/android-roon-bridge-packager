# Distribution and permission boundary

The packager source derives from Muness Castle's android-endpoint-manager at `5bfed50d7739266176c0b6abe2d3f671c8ad8bf8`. Original-work licensing is corrected to personal noncommercial terms in LICENSE. The owner confirmed no original code had been distributed; previous MIT labels were mistaken. Third-party grants remain intact. See LICENSING.md.

Roon's [software terms](https://roon.app/en/termsandconditions) were consulted on 2026-10-13. Section 2.1 addresses copying, incorporating software into other programs, and making software available to others. Direct download and byte identity do not resolve those restrictions. Permission for this repackaging flow has not been established. Written permission from Roon or qualified legal review would be needed to support a broader compliance claim. No certification or endorsement is implied.

The release boundary is the packager APK only. It contains this project's source and Gradle-resolved dependencies, but no Roon runtime or endpoint shell. Vendor archives, packed APKs and private signing keys stay on the device, outside the repository and its releases. Build and CI do not download Roon. Synthetic tests exercise archive handling, packing and signatures. Source/history and APK checks enforce mechanical boundaries; they are not legal approval or proof against deliberately disguised vendor material.

A separately supplied endpoint shell includes native runtime and audio dependencies. Their licenses and corresponding-source obligations need their own review before hosting a shell. This repository does not remove those obligations. See NOTICE.md for packager dependencies.
