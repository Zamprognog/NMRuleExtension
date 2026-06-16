package nmRuleExtension.graphTools;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class SemanticConstraintLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    public void testLoadOwlFile() throws IOException {
        Path owlFile = tempDir.resolve("test.owl");
        String owlContent = "<?xml version=\"1.0\"?>\n" +
                "<rdf:RDF xmlns=\"http://example.org#\"\n" +
                "     xml:base=\"http://example.org\"\n" +
                "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
                "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n" +
                "    <owl:Ontology rdf:about=\"http://example.org\"/>\n" +
                "    <owl:ObjectProperty rdf:about=\"http://example.org#worksAt\"/>\n" +
                "    <owl:Class rdf:about=\"http://example.org#Person\"/>\n" +
                "</rdf:RDF>";
        Files.writeString(owlFile, owlContent);

        SemanticConstraintLoader loader = new SemanticConstraintLoader();
        loader.loadAndExtract(owlFile.toString());
        
        assertFalse(loader.getPropertyConstraints().isEmpty(), "Property constraints should not be empty if OWL was loaded correctly");
    }

    @Test
    public void testLoadTtlFile() throws IOException {
        Path ttlFile = tempDir.resolve("test.ttl");
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n" +
                "<http://example.org#worksAt> a owl:ObjectProperty .\n";
        Files.writeString(ttlFile, ttlContent);

        SemanticConstraintLoader loader = new SemanticConstraintLoader();
        loader.loadAndExtract(ttlFile.toString());
        
        assertFalse(loader.getPropertyConstraints().isEmpty(), "Property constraints should not be empty if TTL was loaded correctly");
    }
}
