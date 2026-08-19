# Third-party notices

Minecraft Machines is an independent add-on. Ahmed Hamed owns the original
Minecraft Machines source code and assets and licenses them under the MIT
License in `LICENSE.md`. That license does not grant ownership of, or relicense,
any project listed below.

The Minecraft Machines JAR is built as an add-on and does not package the
dependencies' Java classes or asset directories. They must be obtained and
installed separately. The repository's animated preview depicts the mods in
use but does not contain their original asset files.

## Simulated and Create Aeronautics

- Project: [Creators-of-Aeronautics/Simulated-Project](https://github.com/Creators-of-Aeronautics/Simulated-Project)
- Pinned revision: [`29e185eabb26ac88b1acdc43120577af41cc05d1`](https://github.com/Creators-of-Aeronautics/Simulated-Project/tree/29e185eabb26ac88b1acdc43120577af41cc05d1)
- Copyright: The Simulated Team / The Creators of Aeronautics
- License: [MIT for code; All Rights Reserved for specified asset directories](https://github.com/Creators-of-Aeronautics/Simulated-Project/blob/29e185eabb26ac88b1acdc43120577af41cc05d1/LICENSE.md)

The upstream repository is linked at `external/Simulated-Project` as a Git
submodule. Its files are not covered by the Minecraft Machines license.

The following Minecraft Machines build files were copied or adapted from the
upstream MIT-licensed build configuration:

- `build.gradle`
- `gradle.properties`
- `settings.gradle.kts`
- `buildSrc/build.gradle`
- `buildSrc/src/main/groovy/multiloader-common.gradle`
- `buildSrc/src/main/groovy/multiloader-loader.gradle`

The required upstream MIT notice for those build files follows:

> MIT License
>
> Copyright (c) The Simulated Team / The Creators of Aeronautics
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

The upstream asset directories named in its license remain All Rights
Reserved. Minecraft Machines does not copy those asset files into its own
source or JAR.

## Sable

- Project: [ryanhcode/sable](https://github.com/ryanhcode/sable)
- Version used: `2.0.0` for Minecraft 1.21.1 / NeoForge
- Creator and copyright holder: RyanHCode
- License: [PolyForm Shield License 1.0.0](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.0-neoforge/LICENSE.md)

Sable is an external build and runtime dependency. It is not authored,
vendored, sublicensed, or bundled by Minecraft Machines.

## Sable Companion

- Project: [ryanhcode/sable-companion](https://github.com/ryanhcode/sable-companion)
- Version used: `1.6.0` for Minecraft 1.21.1
- Authors/vendors: RyanHCode and Ocelot
- Copyright notice in the 1.6.0 binary: Copyright (c) 2026 RyanHCode
- License: [MIT](https://github.com/ryanhcode/sable-companion/blob/652ecf0e051846f3a433dd20126dd4d64ec3793a/LICENSE)

Sable Companion is an external compile-time dependency and is not bundled in
the Minecraft Machines JAR.

## Create and related platform libraries

Minecraft Machines also compiles against or runs with independently developed
projects, including:

- [Create](https://github.com/Creators-of-Create/Create), whose code is MIT and whose assets are separately reserved as described in its [license](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/LICENSE.md)
- [Flywheel](https://github.com/Engine-Room/Flywheel), licensed under MIT
- [Ponder](https://github.com/Creators-of-Create/Ponder)
- [NeoForge](https://github.com/neoforged/NeoForge)
- [Minecraft](https://www.minecraft.net/), owned by Mojang Studios / Microsoft

Their names identify compatibility requirements only. No affiliation or
endorsement is implied. Each dependency remains subject to its own license and
terms.
