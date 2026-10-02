# External dependencies and source references

Pi4J 3.0.4 is a Maven dependency (core, GpioD plugin, Raspberry Pi plugin); it retains its own license. See https://www.pi4j.com/ and the dependency distributions. No Pi4J source is copied here.

FFmpeg is an externally installed executable; its license depends on its build. No FFmpeg binary is included in this source repository.

GPIO FM uses the externally installed `markondej/fm_transmitter` program, https://github.com/markondej/fm_transmitter. Its code and sample recording are not vendored. Review the upstream licensing and compatibility before distributing or deploying a combined product.

The buscrawl adapter was independently implemented from the schemas in Eljah/buscrawl commit 0411483d040356a9a53a7ff254d4bd4b34f021b0. No Spark crawler runtime or real movement datasets are copied. See docs/buscrawl.md.

All included audio generation and route fixtures are synthetic. No commercial music licenses or broadcast authorization are conveyed by this repository. The repository owner should choose the distribution license for this project's new code before third-party redistribution.
