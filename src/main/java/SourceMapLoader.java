import com.atlassian.sourcemap.Mapping;
import com.atlassian.sourcemap.SourceMap;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class that loads source maps from JavaScript code.
 */
public class SourceMapLoader
{
        // Regular expression copied from node-source-map-support v0.5.16 by Evan Wallace
        private static final String sourcemapCommentRegex = "(?://[@#][\\s]*sourceMappingURL=([^\\s'\"]+)[\\s]*$)" +
                "|(?:/\\*[@#][\\s]*sourceMappingURL=([^\\s*'\"]+)[\\s]*(?:\\*/)[\\s]*$)";
        private static final Pattern sourcemapCommentPattern = Pattern.compile( sourcemapCommentRegex,
                                                                                Pattern.MULTILINE );

        // Regular expression copied from node-source-map-support v0.5.16 by Evan Wallace
        private static final String sourcemapDataUrlRegex = "^data:application/json[^,]+base64,";
        private static final Pattern sourcemapDataUrlPattern = Pattern.compile( sourcemapDataUrlRegex );

        /**
         * Extract the first source map URL embedded within JavaScript code.
         *
         * @param javascript String containing JavaScript code
         * @return Optional that contains the source map URL, or empty if the code contains no source map comment
         */
        public static Optional<String> extractSourcemapUrl( String javascript )
        {
                Matcher commentMatcher = sourcemapCommentPattern.matcher( javascript );
                if ( !commentMatcher.find() )
                {
                        return Optional.empty();
                }
                return Optional.of( commentMatcher.group( 1 ) );
        }

        /**
         * Loads the source map from the given URL.
         *
         * @param url     URL to a local source map file, or a {@code data:} URL containing a base64-encoded source map
         * @param baseUrl Base URL to use when {@code url} needs to be resolved as a relative URL
         * @return Contents of the source map file
         */
        public static String load( String url, String baseUrl )
                throws ForbiddenUrlProtocolError, IOException, URISyntaxException
        {
                byte[] rawContent;

                if ( sourcemapDataUrlPattern.matcher( url ).find() )
                {
                        String encodedContent = url.substring( url.indexOf( ',' ) + 1 );
                        rawContent = Base64.getDecoder().decode( encodedContent );
                }
                else
                {
                        URL theBaseUrl = new URL( baseUrl );
                        URL theUrl = new URL( theBaseUrl, url );

                        // Rudimentary security measure
                        if ( !theUrl.toString().startsWith( "file:" ) )
                        {
                                throw new ForbiddenUrlProtocolError( "Cannot load URL: " + theUrl );
                        }

                        Path filePath = Paths.get( theUrl.toURI() );
                        rawContent = Files.readAllBytes( filePath );
                }

                return new String( rawContent, StandardCharsets.UTF_8 );
        }

        /**
         * Generates a formatted stack trace line by extracting the source file name, symbol, line, column from the
         * {@code sourceMap}.
         *
         * @param sourceMap Source map data
         * @param line      Line number (starts at 1)
         * @param column    Column number (starts at 1)
         * @return Formatted stack trace, or an empty value if the {@code sourceMap} does not contain any symbol
         * information for the given {@code line} and {@code column} position
         */
        public static Optional<String> formatSourceStackTrace( final SourceMap sourceMap, final int line,
                                                               final int column )
        {
                final Mapping mapping = sourceMap.getMapping( line - 1, column - 1 );
                if ( mapping == null )
                {
                        return Optional.empty();
                }

                // Generate a stack trace string similar to what Node.js uses
                String trace = String.format( "%s (%s:%d:%d)", mapping.getSourceSymbolName(),
                                              mapping.getSourceFileName(), mapping.getSourceLine() + 1,
                                              mapping.getSourceColumn() + 1 );
                return Optional.of( trace );
        }

        /**
         * Error thrown when the user attempts to load a sourcemap using a forbidden protocol.
         * To avoid downloading arbitrary source maps over the network, we disallow all protocols other than
         * {@code file://} or @{code data:}.
         */
        public static class ForbiddenUrlProtocolError
                extends Exception
        {
                /**
                 * @param message Error message
                 */
                ForbiddenUrlProtocolError( String message )
                {
                        super( message );
                }
        }
}
