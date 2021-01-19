import java.nio.file.Path;
import java.nio.file.Paths;

public class SourcemapTest
{
        public static void main( String[] args )
        {
                // Sample stack trace from Node.js
                final String stackTrace =
                        "TypeError: o.someMethod is not a function\n" +
                        "    at o (src/test/resources/generated.js:1:24)\n" +
                        "    at src/test/resources/generated.js:1:45\n" +
                        "    at Object.<anonymous> (src/test/resources/generated.js:1:55)";

                System.out.printf( "Original stack trace:\n%s\n\n", stackTrace );

                SourceMapStackTraceDecorator sourceMapStackTraceDecorator = new SourceMapStackTraceDecorator( new Path[] {Paths.get( "src/test" )} );
                System.out.printf( "Decorated stack trace:\n%s\n", sourceMapStackTraceDecorator.decorateStackTrace( stackTrace ) );
        }
}
