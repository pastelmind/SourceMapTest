import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.atlassian.sourcemap.Mapping;
import com.atlassian.sourcemap.SourceMap;
import com.atlassian.sourcemap.SourceMapImpl;

public class SourcemapTest
{
        public static void main( String[] args )
        {
                try
                {
                        final Path JS_PATH = Paths.get( "src/test/resources/generated-inline.js" );
                        final String js = readFileFromPath( JS_PATH );

                        final String sourceMapUrl = SourceMapLoader.extractSourcemapUrl( js ).get();
                        System.out.printf( "JS_PATH: %s\n", JS_PATH.toString() );
                        System.out.printf( "JS_PATH -> URI: %s\n", JS_PATH.toUri() );
                        System.out.printf( "JS_PATH -> URI -> URL: %s\n", JS_PATH.toUri().toURL().toString() );

                        final String sourceMapContent = SourceMapLoader.load( sourceMapUrl,
                                                                              JS_PATH.toUri().toURL().toString() );
                        final SourceMap sourceMap = new SourceMapImpl( sourceMapContent );

                        System.out.println( "Using sourcemap: " + sourceMapUrl );

                        // Sample stack trace from Node.js:
                        // TypeError: o.someMethod is not a function
                        //     at o (SourcemapTest/src/test/resources/generated.js:1:24)
                        //     at SourcemapTest/src/test/resources/generated.js:1:45
                        //     at Object.<anonymous> (SourcemapTest/src/test/resources/generated.js:1:55)

                        final int[][] POSITIONS = {{1, 24}, {1, 45}, {1, 55}};
                        for ( int[] position : POSITIONS )
                        {
                                final int line = position[0];
                                final int column = position[1];

                                System.out.printf( "Position in generated file: line %d, column %d\n", line, column );

                                System.out.printf( "Stack trace for source: -> %s\n",
                                                   SourceMapLoader.formatSourceStackTrace( sourceMap, line, column ).get() );
                        }
                }
                catch ( SourceMapLoader.ForbiddenUrlProtocolError | IOException | URISyntaxException e )
                {
                        e.printStackTrace();
                }
        }

        public static String readFileFromPath( final Path path )
                throws IOException
        {
                byte[] content = Files.readAllBytes( path );
                return new String( content, StandardCharsets.UTF_8 );
        }
}
