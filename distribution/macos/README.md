# Digital for macOS

Build with JDK 21+ and Maven:

```sh
distribution/macos/build.sh
distribution/macos/install.sh
open /Applications/Digital.app
```

`build.sh --skip-build` packages an existing `target/Digital.jar`.
Set `JAVA_HOME` and `MAVEN_CMD` to select the build tools.
The bundle includes its own Java runtime, component library and examples.
They reside in `Digital.app/Contents/app`; `.dig` and `.fsm` files open in Digital.
The launcher selects Russian by default; language and theme remain selectable in settings.

This GPL-3.0 distribution combines [Helmut Neemann's Digital](https://github.com/hneemann/Digital),
[nicoladen05's modern interface](https://github.com/nicoladen05/Digital),
and [technokratos's Russian translation](https://github.com/technokratos/Digital).
`Digital.icns` is rendered from the original `src/main/svg/icon.svg`.
The bundle carries `LICENSE`, the project `README.md`, and this attribution.
