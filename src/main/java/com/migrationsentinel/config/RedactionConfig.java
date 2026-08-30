package com.migrationsentinel.config;

import com.migrationsentinel.util.SecretMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Loads the extra redaction patterns from {@code redaction.xml} and hands them to
 * {@link SecretMasker}. The masker keeps its compiled-in defaults as a floor either way, so
 * a missing or malformed file only means "no extra patterns", never "no masking".
 */
@Slf4j
@Component
public class RedactionConfig {

    @EventListener(ApplicationReadyEvent.class)
    public void loadPatterns() {
        try {
            ClassPathResource resource = new ClassPathResource("redaction.xml");
            if (!resource.exists()) {
                return;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(resource.getInputStream());
            NodeList nodes = doc.getElementsByTagName("pattern");

            List<Pattern> patterns = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                String raw = nodes.item(i).getTextContent().trim();
                if (!raw.isEmpty()) {
                    patterns.add(Pattern.compile(raw));
                }
            }
            SecretMasker.configure(patterns);
            log.info("redaction: {} extra pattern(s) loaded from redaction.xml", patterns.size());
        } catch (Exception ex) {
            log.warn("redaction: could not load redaction.xml, using defaults only: {}", ex.getMessage());
        }
    }
}
