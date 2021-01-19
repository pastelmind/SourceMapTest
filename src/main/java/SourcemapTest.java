public class SourcemapTest
{
        public static void main( String[] args )
        {
                // Sample stack trace from Node.js
                final String stackTrace =
                        "TypeError: o.someMethod is not a function\n" +
                        "    at o (src/test/resources/generated.js:1:24)\n"+
                        "    at src/test/resources/generated.js:1:45\n"+
                        "    at Object.<anonymous> (src/test/resources/generated.js:1:55)";

                System.out.printf( "Original stack trace:\n%s\n\n", stackTrace );

                SourceMapLoader sourceMapLoader = new SourceMapLoader();
                System.out.printf("Decorated stack trace:\n%s\n", sourceMapLoader.decorateStackTrace( stackTrace ));
        }
}
