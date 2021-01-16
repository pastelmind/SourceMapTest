# SourcemapTest

This project uses a modified POM file for the [sourcemap](https://mvnrepository.com/artifact/com.atlassian.sourcemap/sourcemap) package. To build this project, sourcemap must be manually installed using the following command:

```
mvn install:install-file -Dfile=src/lib/sourcemap-1.7.7.jar -DpomFile=src/lib/sourcemap-1.7.7.pom
```
