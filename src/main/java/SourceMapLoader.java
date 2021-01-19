import com.atlassian.sourcemap.SourceMap;
import com.atlassian.sourcemap.SourceMapImpl;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <p>Class that loads source maps for JavaScript code.</p>
 *
 * <p>The implementation makes the assumptions:</p>
 *
 * <ul>
 *   <li>The {@link SourceMapLoader} exists for the duration of the JS execution context. When the execution finishes,
 *   the SourceMapLoader should be destroyed. This allows the next execution context to load source maps that are
 *   modified on disk, instead of reusing a stale cache.</li>
 *   <li>The number of source maps loaded during lifetime of a JS execution context is small (<100). This allows us to
 *   use a simple {@link ConcurrentHashMap} instead of a full-blown LRU cache without worrying about memory usage.</li>
 * </ul>
 */
public class SourceMapLoader
{
        // Regular expression copied from node-source-map-support v0.5.16 by Evan Wallace
        private static final String sourceMapCommentRegex = "(?://[@#][\\s]*sourceMappingURL=([^\\s'\"]+)[\\s]*$)" +
                "|(?:/\\*[@#][\\s]*sourceMappingURL=([^\\s*'\"]+)[\\s]*(?:\\*/)[\\s]*$)";
        private static final Pattern sourceMapCommentPattern = Pattern.compile( sourceMapCommentRegex,
                                                                                Pattern.MULTILINE );

        // Regular expression copied from node-source-map-support v0.5.16 by Evan Wallace
        private static final String sourceMapDataUrlRegex = "^data:application/json[^,]+base64,";
        private static final Pattern sourceMapDataUrlPattern = Pattern.compile( sourceMapDataUrlRegex );

        // Pattern for "V8-style" stack trace messages.
        // We cannot support "Rhino-style" stack traces because they don't contain column numbers.
        private static final String stackLineRegex = "^(\\s*)at .*?\\(?(\\S+):(\\d+):(\\d+)\\)?$";
        private static final Pattern stackLinePattern = Pattern.compile( stackLineRegex, Pattern.MULTILINE );

        /**
         * <p>Cache of source map URLs, keyed by absolute paths to JavaScript files. This must not contain a
         * {@code null} value.</p>
         *
         * <p>A value of {@link Optional#empty()} indicates that the script path has been accessed before, but a source
         * map URL could not be extracted for some reason.</p>
         */
        private final ConcurrentMap<Path, Optional<URL>> sourceMapUrlCache = new ConcurrentHashMap<>();
        /**
         * <p>Cache of {@link SourceMap} objects, keyed by source map URLs. Source map URLs are either {@code file:}
         * URLs that point to actual source map files, or base64-encoded {@code data:} URLs that contain source maps.
         * This must not contain a {@code null} value.</p>
         *
         * <p>A value of {@link Optional#empty()} indicates that the URL has been accessed before, but a source map
         * could not be parsed for some reason.</p>
         */
        private final ConcurrentMap<URL, Optional<SourceMap>> sourceMapCache = new ConcurrentHashMap<>();

        private final List<Path> whitelistedPaths;

        /**
         * @param whitelistedPaths Array of whitelisted paths. The loader will only load JavaScript files and source
         *                         maps that are under any of these paths.
         */
        public SourceMapLoader( Path[] whitelistedPaths )
        {
                this.whitelistedPaths = Arrays
                        .stream( whitelistedPaths )
                        .map( Path::normalize )
                        .map( Path::toAbsolutePath )
                        .collect( Collectors.toList() );
        }

        /**
         * <p>Decorates a V8-style JavaScript stack trace by parsing source maps and adding source stack lines below
         * each stack line.</p>
         *
         * <p>Note: This method is NOT idempotent.</p>
         *
         * @param stackTrace V8-style stack trace message
         * @return Decorated stack trace message containing source stack lines
         */
        public String decorateStackTrace( final String stackTrace )
        {
                final String[] stackLines = stackTrace.split( "\n" );
                final ArrayList<String> convertedStackLines = new ArrayList<>();
                for ( String line : stackLines )
                {
                        convertedStackLines.add( line );
                        createSourceStackLine( line ).ifPresent( convertedStackLines::add );
                }
                return String.join( "\n", convertedStackLines );
        }

        /**
         * <p>Creates a source stack line from a V8-style stack line by using the source map.</p>
         *
         * <p>This returns {@link Optional#empty()} if the {@code stackLine} is not a valid stack trace line,
         * or the source map cannot be retrieved.</p>
         *
         * @param stackLine Stack trace line
         * @return Optional containing the source stack line, or empty if the source map could not be retrieved
         */
        private Optional<String> createSourceStackLine( final String stackLine )
        {
                final Matcher stackLineMatcher = stackLinePattern.matcher( stackLine );
                if ( !stackLineMatcher.find() )
                {
                        return Optional.empty();
                }

                final String leadingWhitespace = stackLineMatcher.group( 1 );
                final Path generatedScriptPath = Paths.get( stackLineMatcher.group( 2 ) );
                final int generatedLine = Integer.parseInt( stackLineMatcher.group( 3 ) ) - 1;
                final int generatedColumn = Integer.parseInt( stackLineMatcher.group( 4 ) ) - 1;

                return loadSourceMapUrlFromScript( generatedScriptPath )
                        .flatMap( this::loadSourceMapFromUrl )
                        .map( srcMap -> srcMap.getMapping( generatedLine, generatedColumn ) )
                        .map( mapping -> String.format( "%s    -> %s (%s:%d:%d)",
                                                        leadingWhitespace,
                                                        mapping.getSourceSymbolName() != null ?
                                                        mapping.getSourceSymbolName() : "<anonymous>",
                                                        mapping.getSourceFileName(),
                                                        mapping.getSourceLine() + 1,
                                                        mapping.getSourceColumn() + 1 ) );
        }

        /**
         * <p>Loads the file at {@code scriptPath} and returns the first source map URL embedded within. If the source
         * map URL is relative, it is resolved to an absolute {@code file://} URL using the {@code scriptPath} as the
         * source origin. The result is cached, so that subsequent calls with the same {@code scriptPath} will return
         * the same value, regardless of success.</p>
         *
         * <p>This assumes that the file is UTF-8 encoded.</p>
         *
         * @param scriptPath Relative or absolute path to a JavaScript file
         * @return Optional containing an absolute source map URL, or empty if the source map URL could not be loaded
         * (e.g. {@code scriptPath} does not exist, or the script does not contain a sourceMappingURL comment)
         */
        private Optional<URL> loadSourceMapUrlFromScript( final Path scriptPath )
        {
                final Path scriptAbsolutePath = scriptPath.normalize().toAbsolutePath();
                if ( !isUnderWhitelistedPath( scriptAbsolutePath ) )
                {
                        return Optional.empty();
                }

                Optional<URL> sourceMapUrl = sourceMapUrlCache.get( scriptAbsolutePath );
                // Valid, since we never store null values in sourceMapUrlCache. A null indicates a cache miss.
                // noinspection OptionalAssignedToNull
                if ( sourceMapUrl == null )
                {
                        sourceMapUrl = Optional.empty();
                        try
                        {
                                final byte[] rawContent = Files.readAllBytes( scriptAbsolutePath );
                                final String content = new String( rawContent, StandardCharsets.UTF_8 );
                                final Optional<String> urlString = parseSourceMapUrlString( content );

                                // Using isPresent() and get() is suboptimal practice, but Java doesn't allow throwing
                                // checked exceptions, so we're short on alternatives here.
                                if ( urlString.isPresent() )
                                {
                                        // If the sourceMappingURL is a relative URL, use the script's path as the
                                        // "source origin"
                                        // (terminology adopted from Source Map specification v3)
                                        final URL sourceOrigin = scriptAbsolutePath.toUri().toURL();
                                        sourceMapUrl = Optional.of( new URL( sourceOrigin, urlString.get() ) );
                                }
                        }
                        catch ( IOException e )
                        {
                                // File does not exist, or the source map URL is invalid
                                System.err.printf(
                                        "Skipping stack trace line because no source map URL could be parsed from %s\n",
                                        scriptPath );
                                e.printStackTrace();
                        }
                        sourceMapUrlCache.put( scriptPath, sourceMapUrl );
                }
                return sourceMapUrl;
        }

        /**
         * <p></p>Loads the given URL as a source map file. The result is cached, so that subsequent calls with the same
         * {@code url} will return the same value, regardless of success.</p>
         *
         * <p>This assumes that the file is UTF-8 encoded.</p>
         *
         * @param url Absolute {@code file:} URL to a local file, or a base64-encoded {@code data:} URL
         * @return Optional containing a {@link SourceMap} object, or empty if the source map could not be loaded
         * * (e.g. {@code url} does not exist, or the script does not contain a sourceMappingURL
         * comment)
         */
        private Optional<SourceMap> loadSourceMapFromUrl( final URL url )
        {
                Optional<SourceMap> sourceMap = sourceMapCache.get( url );
                // Valid, since we never store null values in sourceMapCache. A null indicates a cache miss.
                // noinspection OptionalAssignedToNull
                if ( sourceMap == null )
                {
                        sourceMap = Optional.empty();
                        try
                        {
                                final String content = loadFileContentFromUrl( url );
                                sourceMap = Optional.of( new SourceMapImpl( content ) );
                        }
                        catch ( ForbiddenUrlProtocolError | IOException | URISyntaxException e )
                        {
                                System.err.printf(
                                        "Skipping stack trace line because no source map could be loaded from %s\n",
                                        url );
                                e.printStackTrace();
                        }
                        sourceMapCache.put( url, sourceMap );
                }
                return sourceMap;
        }

        /**
         * Checks if the given path is under any of the whitelisted directories.
         *
         * @param path Path to check
         * @return Whether the {@code path} is inside any of the whitelisted directories
         */
        private boolean isUnderWhitelistedPath( final Path path )
        {
                final Path normalizedPath = path.normalize().toAbsolutePath();
                return whitelistedPaths.stream().anyMatch( normalizedPath::startsWith );
        }

        /**
         * Extract the first source map URL embedded within JavaScript code.
         *
         * @param javascript String containing JavaScript code
         * @return Optional that contains the source map URL, or empty if the code contains no source map comment
         */
        public static Optional<String> parseSourceMapUrlString( String javascript )
        {
                Matcher commentMatcher = sourceMapCommentPattern.matcher( javascript );
                if ( !commentMatcher.find() )
                {
                        return Optional.empty();
                }
                return Optional.of( commentMatcher.group( 1 ) );
        }

        /**
         * Loads a file (JavaScript code or source map) from a URL.
         *
         * @param url Absolute {@code file:} URL to a local file, or a base64-encoded {@code data:} URL
         * @return Contents of the file
         * @throws ForbiddenUrlProtocolError If the URL is invalid
         * @throws IOException               If the file cannot be opened
         * @throws URISyntaxException        If the URL is malformed
         */
        private String loadFileContentFromUrl( URL url )
                throws ForbiddenUrlProtocolError, IOException, URISyntaxException
        {
                final byte[] rawContent;
                final String urlString = url.toString();

                if ( sourceMapDataUrlPattern.matcher( urlString ).find() )
                {
                        String encodedContent = urlString.substring( urlString.indexOf( ',' ) + 1 );
                        rawContent = Base64.getDecoder().decode( encodedContent );
                }
                // Rudimentary security measure
                else if ( urlString.startsWith( "file:" ) && isUnderWhitelistedPath( Paths.get( url.toURI() ) ) )
                {
                        Path filePath = Paths.get( url.toURI() );
                        rawContent = Files.readAllBytes( filePath );
                }
                else
                {
                        throw new ForbiddenUrlProtocolError( "Cannot load URL: " + urlString );
                }

                return new String( rawContent, StandardCharsets.UTF_8 );
        }

        /**
         * Error thrown when the user attempts to load a source map using a forbidden protocol.
         */
        public static class ForbiddenUrlProtocolError
                extends Exception
        {
                ForbiddenUrlProtocolError( String message )
                {
                        super( message );
                }
        }
}
