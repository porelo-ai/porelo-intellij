package org.fuin.porelo.intellij.sarif;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Reads one page of a waiting push's review comments, SARIF 2.1.0 in Porelo's review profile 1,
 * into notes, or refuses the whole page.
 *
 * <p>The texts come from tasks, which are not trusted, so the reader is closed: a key, value or
 * shape the profile does not name refuses the page, and a refusal names the rule and the place but
 * never repeats the page's text. A page must also be the one asked for: its project, repository,
 * push, reviewed commit and base are compared with the reader's.
 */
public final class ProfileNoteReader implements NoteReader {

    /** The most bytes of UTF-8 a page takes. */
    static final int MAX_PAGE_BYTES = 1 << 20;

    /** The most results a page carries. */
    static final int MAX_RESULTS = 100;

    /** The most bytes of UTF-8 a text, a path or a replacement takes. */
    static final int MAX_TEXT_BYTES = 8192;

    /** The most lines a region spans. */
    static final int MAX_REGION_LINES = 100;

    /** The most characters a thread id or a task id takes. */
    static final int MAX_ID_LENGTH = 64;

    // The daemon's own form of a task's name; it bounds no length, so this one is generous.
    private static final Pattern TASK_NAME = Pattern.compile("[a-z0-9]([a-z0-9_-]*[a-z0-9])?");

    private static final int MAX_TASK_NAME_LENGTH = 255;

    private static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

    private static final Pattern GUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Set<String> COMPLETENESS =
            Set.of("complete", "partial", "failed", "unknown");

    private static final String RUN = "/runs/0";

    private static final String BASE = "porelo/baseRevisionId";

    private final String project;

    private final String repository;

    private final String push;

    private final String commit;

    private final @Nullable String base;

    /**
     * Creates a reader for the pages of one waiting push.
     *
     * @param project The push's project.
     * @param repository The push's repository.
     * @param push The push's name.
     * @param commit The reviewed commit, in full.
     * @param base The commit the push is compared with; absent when the review names none.
     */
    public ProfileNoteReader(
            final String project,
            final String repository,
            final String push,
            final String commit,
            final @Nullable String base) {
        this.project = project;
        this.repository = repository;
        this.push = push;
        this.commit = commit;
        this.base = base;
    }

    /**
     * Reads a page.
     *
     * @param sarif One page, as the daemon sent it.
     * @return Its notes, in the page's order, and its completeness.
     * @throws SarifRefusedException If the page is not one of the profile's or not this push's;
     *     nothing of it is read.
     */
    @Override
    public Page read(final String sarif) {
        // Measured before parsing, so an oversized page costs no parse.
        if (utf8Exceeds(sarif, MAX_PAGE_BYTES)) {
            throw refused("size", "", "the page is over 1 MiB of UTF-8");
        }
        final Map<?, ?> root = object(parse(sarif), "");
        keys(root, "", Set.of("version", "$schema", "properties", "runs"), Set.of());
        fixed(root, "version", "2.1.0", "", "profile");
        fixed(root, "$schema", SCHEMA, "", "profile");
        final Map<?, ?> properties = object(root.get("properties"), "/properties");
        keys(properties, "/properties", Set.of("porelo/profileVersion"), Set.of());
        fixed(properties, "porelo/profileVersion", "1", "/properties", "profile");
        final List<?> runs = array(root.get("runs"), "/runs");
        if (runs.size() != 1) {
            throw refused("run", "/runs", "a page holds exactly one run");
        }
        return run(object(runs.get(0), RUN));
    }

    private static @Nullable Object parse(final String sarif) {
        try {
            return Json.parseExact(sarif);
        } catch (final JsonException e) {
            // The parser's message quotes the page, so only its kind is passed on.
            throw refused(
                    "json",
                    "",
                    "the page is not well-formed JSON, repeats a key or nests too deep");
        }
    }

    private Page run(final Map<?, ?> run) {
        keys(
                run,
                RUN,
                Set.of("tool", "versionControlProvenance", "columnKind", "properties", "results"),
                Set.of());
        final Map<?, ?> tool = object(run.get("tool"), RUN + "/tool");
        keys(tool, RUN + "/tool", Set.of("driver"), Set.of());
        final Map<?, ?> driver = object(tool.get("driver"), RUN + "/tool/driver");
        keys(driver, RUN + "/tool/driver", Set.of("name"), Set.of());
        fixed(driver, "name", "Porelo", RUN + "/tool/driver", "profile");
        provenance(run.get("versionControlProvenance"), RUN + "/versionControlProvenance");
        fixed(run, "columnKind", "unicodeCodePoints", RUN, "profile");
        final String completeness = runProperties(run.get("properties"), RUN + "/properties");
        final List<?> results = array(run.get("results"), RUN + "/results");
        if (results.size() > MAX_RESULTS) {
            throw refused("size", RUN + "/results", "a page holds at most 100 results");
        }
        final Set<String> guids = new HashSet<>();
        final List<Note> notes = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            notes.add(note(results.get(i), RUN + "/results/" + i, guids));
        }
        return new Page(notes, completeness);
    }

    private void provenance(final @Nullable Object value, final String at) {
        final List<?> provenance = array(value, at);
        if (provenance.size() != 1) {
            throw refused("run", at, "a run names exactly one repository");
        }
        final Map<?, ?> named = object(provenance.get(0), at + "/0");
        keys(named, at + "/0", Set.of("repositoryUri", "revisionId"), Set.of());
        // The daemon percent-encodes both names into the URN, so they are compared encoded.
        final String uri = "urn:porelo:repository:" + encode(project) + ":" + encode(repository);
        if (!uri.equals(string(named.get("repositoryUri"), at + "/0/repositoryUri"))) {
            throw refused("push", at + "/0/repositoryUri", "the page is for another repository");
        }
        if (!commit.equals(string(named.get("revisionId"), at + "/0/revisionId"))) {
            throw refused(
                    "push",
                    at + "/0/revisionId",
                    "the push moved since it was shown; read it again");
        }
    }

    private String runProperties(final @Nullable Object value, final String at) {
        final Map<?, ?> properties = object(value, at);
        keys(
                properties,
                at,
                Set.of("porelo/project", "porelo/repository", "porelo/push", "porelo/completeness"),
                Set.of(BASE));
        same(properties, "porelo/project", project, at, "the page is for another project");
        same(properties, "porelo/repository", repository, at, "the page is for another repository");
        same(properties, "porelo/push", push, at, "the page is for another push");
        final @Nullable Object named = properties.get(BASE);
        final @Nullable String expected = base;
        // The daemon leaves the base out when its push names none, and the diff shown is then
        // against the cache's; a base named here must be the one shown.
        final boolean other = named != null && !string(named, pointer(at, BASE)).equals(expected);
        if (other) {
            throw refused(
                    "push", pointer(at, BASE), "the push moved since it was shown; read it again");
        }
        return oneOf(properties, "porelo/completeness", COMPLETENESS, at, "profile");
    }

    private static Note note(
            final @Nullable Object value, final String at, final Set<String> guids) {
        final Map<?, ?> result = object(value, at);
        keys(
                result,
                at,
                Set.of("guid", "kind", "level", "message", "locations", "properties"),
                Set.of("fixes"));
        final String guid = guid(result.get("guid"), at + "/guid", guids);
        fixed(result, "kind", "review", at, "profile");
        fixed(result, "level", "none", at, "profile");
        final Map<?, ?> message = object(result.get("message"), at + "/message");
        keys(message, at + "/message", Set.of("text"), Set.of());
        final String text =
                message(string(message.get("text"), at + "/message/text"), at + "/message/text");
        final Place place = place(result.get("locations"), at + "/locations");
        final Map<?, ?> properties = object(result.get("properties"), at + "/properties");
        final Note.Threading thread = threading(properties, at + "/properties");
        final Note.Origin origin = origin(properties, at + "/properties");
        final String side =
                oneOf(
                        properties,
                        "porelo/diffSide",
                        Set.of("old", "new"),
                        at + "/properties",
                        "location");
        final @Nullable Object fixes = result.get("fixes");
        if (fixes != null) {
            if (thread.replyTo() != null) {
                throw refused("fix", at + "/fixes", "a reply carries no fix");
            }
            // Checked so the page is the daemon's, but the plugin shows no suggestion yet.
            fixes(fixes, at + "/fixes", place);
        }
        return new Note(
                guid,
                "review",
                "none",
                text,
                new Note.Anchor("line", place.path(), side, place.region()),
                thread,
                origin,
                false,
                null);
    }

    private static String guid(
            final @Nullable Object value, final String at, final Set<String> guids) {
        final String guid = string(value, at);
        if (!GUID.matcher(guid).matches()) {
            throw refused("guid", at, "a guid is not a lowercase UUID");
        }
        if (!guids.add(guid)) {
            throw refused("guid", at, "a guid is given twice in the page");
        }
        return guid;
    }

    private static Note.Threading threading(final Map<?, ?> properties, final String at) {
        keys(
                properties,
                at,
                Set.of(
                        "porelo/anchorKind",
                        "porelo/diffSide",
                        "porelo/threadId",
                        "porelo/threadState",
                        "porelo/sourceKind"),
                Set.of("porelo/replyTo", "porelo/taskId"));
        fixed(properties, "porelo/anchorKind", "line", at, "location");
        final String id = id(properties.get("porelo/threadId"), pointer(at, "porelo/threadId"));
        final @Nullable Object answered = properties.get("porelo/replyTo");
        if (answered != null && !id(answered, pointer(at, "porelo/replyTo")).equals(id)) {
            throw refused(
                    "thread", pointer(at, "porelo/replyTo"), "a reply answers another thread");
        }
        final String state =
                oneOf(properties, "porelo/threadState", Set.of("open", "resolved"), at, "thread");
        return new Note.Threading(id, answered == null ? null : id, state);
    }

    private static Note.Origin origin(final Map<?, ?> properties, final String at) {
        final String kind =
                oneOf(
                        properties,
                        "porelo/sourceKind",
                        Set.of("owner-account", "task"),
                        at,
                        "source");
        final @Nullable Object task = properties.get("porelo/taskId");
        if (kind.equals("owner-account")) {
            if (task != null) {
                throw refused(
                        "source", pointer(at, "porelo/taskId"), "the owner account has no task id");
            }
            return new Note.Origin(kind, null, "owner account");
        }
        if (task == null) {
            throw refused("source", at, "a task's item names its task");
        }
        return new Note.Origin(kind, null, "task " + taskName(task, pointer(at, "porelo/taskId")));
    }

    private static String id(final @Nullable Object value, final String at) {
        final String id = string(value, at);
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            throw refused("thread", at, "an id is not 1 to 64 characters");
        }
        characters(id, at);
        // Shown beside a writer's name, where a line break would start a writer line of its own.
        if (id.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw refused("thread", at, "an id holds a space or a control character");
        }
        return id;
    }

    /** A task's name as the daemon gives one, shown as the writer of its notes. */
    private static String taskName(final @Nullable Object value, final String at) {
        final String name = string(value, at);
        if (name.length() > MAX_TASK_NAME_LENGTH || !TASK_NAME.matcher(name).matches()) {
            throw refused("source", at, "a task id is no task's name");
        }
        return name;
    }

    /** Undoes exactly SARIF's escaping of a message, then holds the text to the profile's rule. */
    private static String message(final String written, final String at) {
        final StringBuilder text = new StringBuilder(written.length());
        for (int i = 0; i < written.length(); i++) {
            final char c = written.charAt(i);
            final char next = i + 1 < written.length() ? written.charAt(i + 1) : '\0';
            switch (c) {
                case '\\' -> {
                    if (next != '\\' && next != '[' && next != ']') {
                        throw refused("text", at, "a backslash escapes nothing SARIF escapes");
                    }
                    text.append(next);
                    i++;
                }
                case '{', '}' -> {
                    if (next != c) {
                        throw refused("text", at, "a literal brace is not doubled");
                    }
                    text.append(c);
                    i++;
                }
                // An unescaped bracket would start a link, which the daemon never writes.
                case '[', ']' -> throw refused("text", at, "a square bracket is not escaped");
                default -> text.append(c);
            }
        }
        final String given = text.toString();
        if (given.isBlank()) {
            throw refused("text", at, "a text is empty");
        }
        bounded(given, at);
        return given;
    }

    /** A result's file, as written and as decoded, and its lines. */
    private record Place(String uri, String path, Note.Region region) {}

    private static Place place(final @Nullable Object value, final String at) {
        final List<?> locations = array(value, at);
        if (locations.size() != 1) {
            throw refused("location", at, "a result has exactly one location");
        }
        final Map<?, ?> location = object(locations.get(0), at + "/0");
        keys(location, at + "/0", Set.of("physicalLocation"), Set.of());
        final String where = at + "/0/physicalLocation";
        final Map<?, ?> physical = object(location.get("physicalLocation"), where);
        keys(physical, where, Set.of("artifactLocation", "region"), Set.of());
        final String uri = uri(physical.get("artifactLocation"), where + "/artifactLocation");
        return new Place(
                uri,
                path(uri, where + "/artifactLocation/uri"),
                region(physical.get("region"), where + "/region"));
    }

    private static String uri(final @Nullable Object value, final String at) {
        final Map<?, ?> artifact = object(value, at);
        keys(artifact, at, Set.of("uri"), Set.of());
        return string(artifact.get("uri"), at + "/uri");
    }

    /**
     * Decodes a canonical repository-relative URI reference: every byte but the unreserved ones and
     * {@code /} percent-encoded in uppercase hex, and only those, so one path has one form.
     */
    private static String path(final String uri, final String at) {
        if (uri.isEmpty() || uri.startsWith("/")) {
            throw refused("location", at, "a path is empty or not relative");
        }
        final ByteBuffer bytes = ByteBuffer.allocate(uri.length());
        for (final String segment : uri.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw refused("location", at, "a path has an empty, . or .. segment");
            }
            if (bytes.position() > 0) {
                bytes.put((byte) '/');
            }
            segment(segment, bytes, at);
        }
        bytes.flip();
        final String path;
        try {
            path =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(bytes)
                            .toString();
        } catch (final CharacterCodingException e) {
            throw refused("location", at, "a path is not UTF-8");
        }
        bounded(path, at);
        // Shown in the list of threads not at a line, where a line break would start a line.
        if (path.codePoints().anyMatch(Character::isISOControl)) {
            throw refused("location", at, "a path holds a control character");
        }
        return path;
    }

    private static void segment(final String segment, final ByteBuffer bytes, final String at) {
        for (int i = 0; i < segment.length(); i++) {
            final char c = segment.charAt(i);
            if (unreserved(c)) {
                bytes.put((byte) c);
            } else if (c == '%'
                    && i + 2 < segment.length()
                    && upperHex(segment.charAt(i + 1))
                    && upperHex(segment.charAt(i + 2))) {
                final int b = Integer.parseInt(segment.substring(i + 1, i + 3), 16);
                // A byte that stands as itself, or a slash, encoded gives a path a second form.
                if (b == '/' || unreserved((char) b)) {
                    throw refused("location", at, "a path encodes a byte it writes as itself");
                }
                bytes.put((byte) b);
                i += 2;
            } else {
                throw refused("location", at, "a path is not a canonical URI reference");
            }
        }
    }

    private static boolean unreserved(final char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '.'
                || c == '_'
                || c == '~';
    }

    private static boolean upperHex(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }

    private static Note.Region region(final @Nullable Object value, final String at) {
        final Map<?, ?> region = object(value, at);
        keys(region, at, Set.of("startLine", "endLine"), Set.of());
        final long start = integer(region.get("startLine"), at + "/startLine");
        final long end = integer(region.get("endLine"), at + "/endLine");
        if (start < 1 || end < start || end > Integer.MAX_VALUE) {
            throw refused("location", at, "a region does not run from a line of 1 on forward");
        }
        if (end - start + 1 > MAX_REGION_LINES) {
            throw refused("location", at, "a region spans over 100 lines");
        }
        return new Note.Region((int) start, (int) end);
    }

    /** One change of the comment's own file, replacing exactly its lines, as the daemon writes. */
    private static void fixes(final Object value, final String at, final Place place) {
        final Map<?, ?> fix = single(value, at);
        keys(fix, at + "/0", Set.of("artifactChanges"), Set.of());
        final String changes = at + "/0/artifactChanges";
        final Map<?, ?> change = single(fix.get("artifactChanges"), changes);
        keys(change, changes + "/0", Set.of("artifactLocation", "replacements"), Set.of());
        final String file = changes + "/0/artifactLocation";
        if (!place.uri().equals(uri(change.get("artifactLocation"), file))) {
            throw refused("fix", file, "a fix changes another file");
        }
        final String replacements = changes + "/0/replacements";
        final Map<?, ?> replacement = single(change.get("replacements"), replacements);
        keys(
                replacement,
                replacements + "/0",
                Set.of("deletedRegion", "insertedContent"),
                Set.of());
        final String deleted = replacements + "/0/deletedRegion";
        if (!place.region().equals(region(replacement.get("deletedRegion"), deleted))) {
            throw refused("fix", deleted, "a fix replaces other lines");
        }
        final String inserted = replacements + "/0/insertedContent";
        final Map<?, ?> content = object(replacement.get("insertedContent"), inserted);
        keys(content, inserted, Set.of("text"), Set.of());
        bounded(string(content.get("text"), inserted + "/text"), inserted + "/text");
    }

    private static Map<?, ?> single(final @Nullable Object value, final String at) {
        final List<?> list = array(value, at);
        if (list.size() != 1) {
            throw refused("fix", at, "a fix holds exactly one of each part");
        }
        return object(list.get(0), at + "/0");
    }

    /** At most 8 KiB of UTF-8, and only characters a person sees as what they are. */
    private static void bounded(final String text, final String at) {
        characters(text, at);
        if (utf8Exceeds(text, MAX_TEXT_BYTES)) {
            throw refused("text", at, "a text is over 8 KiB of UTF-8");
        }
    }

    private static void characters(final String text, final String at) {
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\n' || c == '\t') {
                continue;
            }
            final int type = Character.getType(c);
            if (type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw refused(
                        "text",
                        at,
                        "a text holds a control, format or separator character or a lone"
                                + " surrogate");
            }
        }
    }

    // Counted rather than encoded, so a huge string is not copied to learn it is too long.
    private static boolean utf8Exceeds(final String text, final long limit) {
        long bytes = 0;
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
            if (bytes > limit) {
                return true;
            }
        }
        return false;
    }

    /** Percent-encodes every byte but the unreserved ones, as the daemon writes a name. */
    private static String encode(final String text) {
        final StringBuilder out = new StringBuilder();
        for (final byte b : text.getBytes(StandardCharsets.UTF_8)) {
            final char c = (char) (b & 0xff);
            if (unreserved(c)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** Every required key present, and no key that is neither required nor optional. */
    private static void keys(
            final Map<?, ?> map,
            final String at,
            final Set<String> required,
            final Set<String> optional) {
        for (final Object key : map.keySet()) {
            if (!required.contains(key) && !optional.contains(key)) {
                // The pointer names the object, never the key: the key is the page's text.
                throw refused("shape", at, "an object holds a key the profile does not name");
            }
        }
        for (final String key : required) {
            if (!map.containsKey(key)) {
                throw refused("shape", at, "an object lacks the key " + key);
            }
        }
    }

    private static void fixed(
            final Map<?, ?> map,
            final String key,
            final String expected,
            final String at,
            final String rule) {
        if (!expected.equals(string(map.get(key), pointer(at, key)))) {
            throw refused(rule, pointer(at, key), key + " is not the profile's value");
        }
    }

    private static void same(
            final Map<?, ?> map,
            final String key,
            final String expected,
            final String at,
            final String message) {
        if (!expected.equals(string(map.get(key), pointer(at, key)))) {
            throw refused("push", pointer(at, key), message);
        }
    }

    private static String oneOf(
            final Map<?, ?> map,
            final String key,
            final Set<String> words,
            final String at,
            final String rule) {
        final String word = string(map.get(key), pointer(at, key));
        if (!words.contains(word)) {
            throw refused(rule, pointer(at, key), key + " is not one of the profile's words");
        }
        return word;
    }

    private static Map<?, ?> object(final @Nullable Object value, final String at) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw refused("shape", at, "a value is not an object");
    }

    private static List<?> array(final @Nullable Object value, final String at) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw refused("shape", at, "a value is not an array");
    }

    private static String string(final @Nullable Object value, final String at) {
        if (value instanceof String text) {
            return text;
        }
        throw refused("shape", at, "a value is not a string");
    }

    private static long integer(final @Nullable Object value, final String at) {
        if (value instanceof Long number) {
            return number;
        }
        throw refused("shape", at, "a value is not a whole number");
    }

    /** A JSON pointer to one of the profile's own keys, several of which hold a slash. */
    private static String pointer(final String at, final String key) {
        return at + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private static SarifRefusedException refused(
            final String rule, final String at, final String message) {
        return new SarifRefusedException(rule, at, message);
    }
}
