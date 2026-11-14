package org.fuin.porelo.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the {@code updatePlugins.xml} the build writes for the custom plugin repository, which an
 * IDE reads to find, offer and download the plugin: a wrong id, version or address installs
 * nothing, or never offers the next snapshot. The IDE also shows a plugin of a custom repository
 * from this entry alone, before anything is downloaded, so what it does and who publishes it must
 * stand there for a person to judge what they install.
 */
class UpdatePluginsTest {

    private static final String DISTRIBUTION =
            "https://fuinorg.jfrog.io/artifactory/porelo-dist-intellij";

    private static final String DOCUMENTATION = "https://porelo-ai.github.io/intellij/";

    @Test
    @DisplayName(
            "updatePlugins.xml names the plugin's id, its version and the address its zip is uploaded to under the version's channel")
    void namesThePluginAndWhereItsZipIs()
            throws IOException, ParserConfigurationException, SAXException {
        final String version = System.getProperty("porelo.version");
        final Path descriptor = Path.of(System.getProperty("porelo.updatePlugins"));
        final Path zip = Path.of(System.getProperty("porelo.zip"));

        final Element plugin = plugin(descriptor);

        assertThat(plugin.getAttribute("id"))
                .as("the plugin's id, as plugin.xml names it")
                .isEqualTo(idInPluginXml());
        assertThat(plugin.getAttribute("version"))
                .as("the version the build gives the plugin")
                .isEqualTo(version);
        assertThat(zip).as("the zip the build made, which Publish uploads").isRegularFile();
        assertThat(plugin.getAttribute("url"))
                .as("where the zip is uploaded")
                .isEqualTo(
                        DISTRIBUTION
                                + (version.contains("-SNAPSHOT") ? "/snapshots/" : "/releases/")
                                + version
                                + "/"
                                + zip.getFileName());
        assertThat(
                        ((Element) plugin.getElementsByTagName("idea-version").item(0))
                                .getAttribute("since-build"))
                .as("the oldest IntelliJ IDEA the plugin is built for")
                .isEqualTo(gradleProperty("pluginSinceBuild"));
    }

    @Test
    @DisplayName(
            "the plugin's descriptor in the zip says what it does, links its documentation and its changes, and names its vendor")
    void theZipsDescriptorSaysWhatThePluginIs() throws Exception {
        final Element descriptor = descriptorInZip(Path.of(System.getProperty("porelo.zip")));

        assertThat(text(descriptor, "description"))
                .as(
                        "the Overview a person reads in Settings > Plugins, with the documentation's link")
                .contains(DOCUMENTATION);
        assertThat(text(descriptor, "change-notes"))
                .as("What's New, with where the changes are listed")
                .contains("https://");
        final Element vendor = only(descriptor, "vendor");
        assertThat(vendor.getTextContent().strip()).as("the vendor's name").isEqualTo("Porelo");
        assertThat(vendor.getAttribute("url")).as("the vendor's address").startsWith("https://");
    }

    @Test
    @DisplayName(
            "the change notes in the zip name every changelog entry of the plugin's version, so What's New says what changed")
    void theChangeNotesNameEveryEntryOfTheVersion() throws Exception {
        final String version = System.getProperty("porelo.version");
        final String notes =
                text(descriptorInZip(Path.of(System.getProperty("porelo.zip"))), "change-notes");

        final List<String> titles = titlesOf(version);

        // Right after a release a snapshot has no entry yet; a release always has.
        if (titles.isEmpty()) {
            assertThat(version).as("a version with no changelog entry").contains("-SNAPSHOT");
            return;
        }
        assertThat(readable(notes)).as("What's New of %s", version).contains(titles);
    }

    @Test
    @DisplayName(
            "updatePlugins.xml carries the description, change notes and vendor of the descriptor in the zip, which the IDE shows before it downloads anything")
    void theRepositoryEntryCarriesWhatThePluginIs() throws Exception {
        final Element served = plugin(Path.of(System.getProperty("porelo.updatePlugins")));
        final Element built = descriptorInZip(Path.of(System.getProperty("porelo.zip")));

        assertThat(text(served, "description"))
                .as("the Overview the repository serves")
                .isEqualTo(text(built, "description"));
        assertThat(text(served, "change-notes"))
                .as("What's New the repository serves")
                .isEqualTo(text(built, "change-notes"));
        assertThat(only(served, "vendor").getTextContent().strip())
                .as("the vendor the repository serves")
                .isEqualTo(only(built, "vendor").getTextContent().strip());
        assertThat(only(served, "vendor").getAttribute("url"))
                .as("the vendor's address the repository serves")
                .isEqualTo(only(built, "vendor").getAttribute("url"));
    }

    /** The HTML's text as a reader sees it: tags gone, entities read, lines joined. */
    private static String readable(String html) {
        return html.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");
    }

    /** The titles of a version's entries, as their text reads once the code marks are gone. */
    private static List<String> titlesOf(String version) throws IOException {
        final Path directory =
                Path.of("changelog", version.contains("-SNAPSHOT") ? "unreleased" : "v" + version);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(entry -> entry.toString().endsWith(".yml"))
                    .map(UpdatePluginsTest::titleOf)
                    .toList();
        }
    }

    /** The entry's whole title: every line but its type, so a wrapped title is read in full. */
    private static String titleOf(Path entry) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(entry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final String title =
                String.join(
                                " ",
                                lines.stream()
                                        .filter(line -> !line.isBlank())
                                        .filter(line -> !line.startsWith("type: "))
                                        .toList())
                        .strip();
        assertThat(title).as("the title of %s", entry).startsWith("title: ");
        return title.substring("title: ".length()).replace("`", "").replaceAll("\\s+", " ");
    }

    private static Element descriptorInZip(Path zip) throws Exception {
        try (ZipInputStream outer = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = outer.getNextEntry();
                    entry != null;
                    entry = outer.getNextEntry()) {
                if (!entry.getName().endsWith(".jar")) {
                    continue;
                }
                try (ZipInputStream inner =
                        new ZipInputStream(new ByteArrayInputStream(outer.readAllBytes()))) {
                    for (ZipEntry file = inner.getNextEntry();
                            file != null;
                            file = inner.getNextEntry()) {
                        if (file.getName().equals("META-INF/plugin.xml")) {
                            return parse(inner.readAllBytes()).getDocumentElement();
                        }
                    }
                }
            }
        }
        throw new AssertionError("no jar in " + zip + " holds META-INF/plugin.xml");
    }

    private static Element only(Element parent, String tag) {
        final NodeList found = parent.getElementsByTagName(tag);
        assertThat(found.getLength())
                .as("the <%s> elements under <%s>", tag, parent.getTagName())
                .isEqualTo(1);
        return (Element) found.item(0);
    }

    private static String text(Element parent, String tag) {
        return only(parent, tag).getTextContent().strip();
    }

    private static Document parse(byte[] xml)
            throws IOException, ParserConfigurationException, SAXException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element plugin(Path descriptor)
            throws IOException, ParserConfigurationException, SAXException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Document document;
        try (InputStream in = Files.newInputStream(descriptor)) {
            document = factory.newDocumentBuilder().parse(in);
        }
        assertThat(document.getElementsByTagName("plugin").getLength())
                .as("the plugins it names")
                .isEqualTo(1);
        return (Element) document.getElementsByTagName("plugin").item(0);
    }

    private static String idInPluginXml() throws IOException {
        final Matcher id =
                Pattern.compile("<id>([^<]+)</id>")
                        .matcher(
                                Files.readString(
                                        Path.of("src/main/resources/META-INF/plugin.xml")));
        assertThat(id.find()).as("plugin.xml names an id").isTrue();
        return id.group(1);
    }

    private static String gradleProperty(String name) throws IOException {
        final Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("gradle.properties"))) {
            properties.load(in);
        }
        return properties.getProperty(name);
    }
}
