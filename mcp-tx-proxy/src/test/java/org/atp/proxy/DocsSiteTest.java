package org.atp.proxy;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsSiteTest {
    private static final Pattern REF = Pattern.compile("(?:src|href)=\"([^\"]+)\"");
    private static final Pattern IMG = Pattern.compile("<img\\s+([^>]+)>");

    @Test
    void pagesResolveAndDiagramsAreSvg() throws Exception {
        Path docs = Path.of("..", "docs").toAbsolutePath().normalize();
        if (!Files.isDirectory(docs)) {
            docs = Path.of("docs").toAbsolutePath().normalize();
        }
        assertTrue(Files.isDirectory(docs), docs.toString());
        List<Path> pages;
        try (var walk = Files.walk(docs)) {
            pages = walk.filter(path -> path.toString().endsWith(".html")).sorted().toList();
        }
        assertEquals(11, pages.size());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        String home = Files.readString(docs.resolve("index.html"));
        assertTrue(home.contains("assets/hero-saga.svg"));
        assertTrue(home.contains("assets/icon-branch.svg"));
        for (Path page : pages) {
            String text = Files.readString(page);
            assertTrue(text.contains("<title>"), page.toString());
            assertTrue(text.contains("site.css"), page.toString());
            assertTrue(text.contains("mark.svg"), page.toString());
            Matcher refs = REF.matcher(text);
            while (refs.find()) {
                String ref = refs.group(1);
                if (ref.startsWith("http:") || ref.startsWith("https:") || ref.startsWith("mailto:") || ref.startsWith("#")) {
                    continue;
                }
                String clean = ref.split("#")[0];
                if (clean.isEmpty()) {
                    continue;
                }
                Path target = page.getParent().resolve(clean).normalize();
                assertTrue(Files.isRegularFile(target), page.getFileName() + " -> " + ref);
            }
            Matcher images = IMG.matcher(text);
            while (images.find()) {
                String tag = images.group(1);
                Matcher alt = Pattern.compile("alt=\"([^\"]*)\"").matcher(tag);
                assertTrue(alt.find(), page.getFileName() + " image missing alt");
                String src = Pattern.compile("src=\"([^\"]+)\"").matcher(tag).results().findFirst().orElseThrow().group(1);
                if (src.endsWith("mark.svg") || src.contains("icon-")) {
                    assertEquals("", alt.group(1));
                } else {
                    assertTrue(alt.group(1).length() > 20, page.getFileName() + " " + src);
                }
            }
        }
        try (var assets = Files.list(docs.resolve("assets"))) {
            List<Path> drawings = assets.filter(path -> path.toString().endsWith(".svg")).sorted().toList();
            assertEquals(15, drawings.size());
            for (Path drawing : drawings) {
                Document document = factory.newDocumentBuilder().parse(drawing.toFile());
                assertEquals("svg", document.getDocumentElement().getLocalName());
                assertFalse(Files.readString(drawing).contains("<script"));
            }
        }
        for (int lesson = 1; lesson <= 6; lesson++) {
            String name = String.format("%02d-", lesson);
            boolean illustrated = pages.stream()
                    .filter(path -> path.getFileName().toString().startsWith(name))
                    .anyMatch(path -> {
                        try {
                            return Files.readString(path).contains(".svg");
                        } catch (Exception e) {
                            return false;
                        }
                    });
            assertTrue(illustrated, name);
        }
    }
}
