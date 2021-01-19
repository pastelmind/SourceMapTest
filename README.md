# SourcemapTest

This project uses a modified POM file for the [sourcemap](https://mvnrepository.com/artifact/com.atlassian.sourcemap/sourcemap) package. To build this project, sourcemap must be manually installed using the following command:

```
mvn install:install-file -Dfile=src/lib/sourcemap-1.7.7.jar -DpomFile=src/lib/sourcemap-1.7.7.pom
```

The directory `src/test/resources/` contains JavaScript code minified using [terser](https://github.com/terser/terser) 5.5.1. They were generated using the following commands:

```
npx terser \
    --mangle toplevel \
    --source-map includeSources,url=generated.js.map \
    --output ./src/test/resources/generated.js \
    ./src/test/resources/source.js
npx terser \
    --mangle toplevel \
    --source-map includeSources,url=inline \
    --output ./src/test/resources/generated-inline.js \
    ./src/test/resources/source.js
```
