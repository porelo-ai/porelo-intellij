package org.fuin.porelo.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Reads the {@code updatePlugins.xml} the build writes for the custom plugin repository, which an
 * IDE reads to find, offer and download the plugin: a wrong id, version or address installs
 * nothing, or never offers the next snapshot.
 */
class UpdatePluginsTest {

    private static final String DISTRIBUTION =
            "https://fuinorg.jfrog.io/artifactory/porelo-dist-intellij";

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
