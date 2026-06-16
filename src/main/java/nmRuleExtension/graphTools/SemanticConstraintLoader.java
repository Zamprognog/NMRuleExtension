package nmRuleExtension.graphTools;

import org.apache.jena.ontology.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;


import java.io.FileInputStream;
import java.util.*;

public class SemanticConstraintLoader {

    // 1. Entity Type Map: URI -> Set of Type URIs
    private final Map<String, Set<String>> entityTypes = new HashMap<>();

    // 2. Property Constraints Map: Property URI -> Constraint Object
    private final Map<String, PropertyConstraint> propertyConstraints = new HashMap<>();

    //3. Disjoint Classes Map: Class URI -> Set of Disjoint Class URIs
    private final Map<String, Set<String>> disjointClassesMap = new HashMap<>();
    /**
     * Data structure to hold domain, range, and characteristics of a property.
     */
    public static class PropertyConstraint {
        public Set<String> domainClasses = new HashSet<>();
        public Set<String> rangeClasses = new HashSet<>();
        public Set<String> disjointProperties = new HashSet<>();
        public boolean isFunctional = false;
        public boolean isSymmetric = false;
        public boolean isTransitive = false;
        public boolean isInverseFunctional = false;

        @Override
        public String toString() {
            return String.format("Domain:%s | Range:%s | Disjoint:%s | Func:%s | Sym:%s | Trans:%s",
                    domainClasses, rangeClasses, disjointProperties, isFunctional, isSymmetric, isTransitive);
        }
    }

    private Model entityTypesModel;
    /**
     * Loads ontology, runs inference, and extracts data into HashMaps.
     * @param tboxPath Path to the .ttl, .owl, or .rdf file containing property restrictions.
     * @param aboxPath Path to the .ttl, .owl, or .rdf file containing typing information.
     */

    public void loadAndExtract(String tboxPath, String aboxPath) {
        System.out.println("Loading ontology from: " + tboxPath + (aboxPath != null ? " and " + aboxPath : ""));
        
        // Use a lighter reasoner to avoid massive overhead for large ABoxes.
        // OWL_MEM_MICRO_RULE_INF provides property characteristics and class hierarchy.
        OntModelSpec spec = OntModelSpec.OWL_MEM_MICRO_RULE_INF;
        OntModel tboxmodel = ModelFactory.createOntologyModel(spec, null);

        loadModel(tboxmodel, tboxPath);
        entityTypesModel = ModelFactory.createDefaultModel();
        //entityTypesModel = ModelFactory.createOntologyModel();
        if (aboxPath != null) {
            System.out.println("Loading entity types: " + aboxPath);
            loadModel(entityTypesModel, aboxPath);
        }

        System.out.println("Reasoning complete. Extracting constraints...");

        extractPropertyConstraints(tboxmodel);
        extractEntityTypes(tboxmodel, entityTypesModel);

        System.out.println("Extraction complete. Jena model can now be discarded.");
    }

    private void loadModel(Model model, String path) {
        String format = null;
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        
        // Jena handles "ttl", "nt", "rdf", "owl" etc.
        // mapping common extensions to standard Jena format strings
        if (extension.equals("ttl")) format = "TURTLE";
        else if (extension.equals("nt")) format = "N-TRIPLE";
        else if (extension.equals("rdf") || extension.equals("owl")) format = "RDF/XML";
        // for other extensions, format remains null, allowing Jena to auto-detect

        try (FileInputStream in = new FileInputStream(path)) {
            model.read(in, null, format);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads ontology from a single file.
     * @param ontologyPath Path to the .ttl, .owl, or .rdf file.
     */
    public void loadAndExtract(String ontologyPath) {
        loadAndExtract(ontologyPath, null);
    }

    private void extractPropertyConstraints(OntModel model) {
        // Iterate over all Object Properties (relationships between entities)
//        ExtendedIterator<OntProperty> properties = model.listObjectProperties();
        ExtendedIterator<OntProperty> properties = model.listOntProperties();

        while (properties.hasNext()) {
            OntProperty p = properties.next();
            if (p.isAnon()) continue; // Skip blank nodes

            String uri = p.getURI();
            PropertyConstraint constraint = new PropertyConstraint();

            // 1. Extract Domain
            if (p.getDomain() != null && !p.getDomain().isAnon()) {
                constraint.domainClasses.add(p.getDomain().getURI());
            }
            // Handle multiple domains (Union) if present
            ExtendedIterator<? extends OntResource> domains = p.listDomain();
            while(domains.hasNext()) {
                OntResource d = domains.next();
                if(!d.isAnon()) constraint.domainClasses.add(d.getURI());
            }

            // 2. Extract Range
            if (p.getRange() != null && !p.getRange().isAnon()) {
                constraint.rangeClasses.add(p.getRange().getURI());
            }
            ExtendedIterator<? extends OntResource> ranges = p.listRange();
            while(ranges.hasNext()) {
                OntResource r = ranges.next();
                if(!r.isAnon()) constraint.rangeClasses.add(r.getURI());
            }

            // 3. Extract Characteristics
            constraint.isFunctional = p.isFunctionalProperty();
            constraint.isSymmetric = p.isSymmetricProperty();
            constraint.isTransitive = p.isTransitiveProperty();
            constraint.isInverseFunctional = p.isInverseFunctionalProperty();

            // 4. Extract Disjointness
            // Using listPropertyValues with OWL.disjointWith to avoid potential OntProperty mapping issues
            ExtendedIterator<org.apache.jena.rdf.model.RDFNode> disjoints = p.listPropertyValues(model.getProperty("http://www.w3.org/2002/07/owl#propertyDisjointWith"));
            while (disjoints.hasNext()) {
                org.apache.jena.rdf.model.RDFNode d = disjoints.next();
                if (d.isResource() && !d.isAnon()) {
                    constraint.disjointProperties.add(d.asResource().getURI()); // Rename variable in PropertyConstraint to disjointProperties
                }
            }

            propertyConstraints.put(uri, constraint);
        }
    }

    private void extractEntityTypes(OntModel model, Model entityTypesModel) {
        // Step 1: Pre-calculate the class hierarchy (transitive closure of subClassOf)
        Map<String, Set<String>> classHierarchy = new HashMap<>();
        ExtendedIterator<OntClass> classes = model.listClasses();

        while (classes.hasNext()) {
            OntClass cls = classes.next();
            if (cls.isAnon()) continue;
            
            String classUri = cls.getURI();
            Set<String> superClasses = new HashSet<>();

            ExtendedIterator<OntClass> supers = cls.listSuperClasses(false);
            while (supers.hasNext()) {
                OntClass s = supers.next();
                if (!s.isAnon() && !isSystemType(s.getURI())) {
                    superClasses.add(s.getURI());
                }
            }
            classHierarchy.put(classUri, superClasses);
            ExtendedIterator<OntClass> disjointIter = cls.listDisjointWith();
            while (disjointIter.hasNext()) {
                OntClass disj = disjointIter.next();
                if (!disj.isAnon() && !isSystemType(disj.getURI())) {
                    disjointClassesMap.computeIfAbsent(classUri, k -> new HashSet<>()).add(disj.getURI());
                }
            }
        }


        if (entityTypesModel == null || entityTypesModel.isEmpty()) return;
        // Step 2: Iterate over individuals in the raw ABox using basic RDF triples
        // This entirely bypasses listIndividuals() and its hidden reasoning hooks
        StmtIterator typeStatements = entityTypesModel.listStatements(null, RDF.type, (Resource) null);

        while (typeStatements.hasNext()) {
            Statement stmt = typeStatements.next();
            if (stmt.getSubject().isAnon() || !stmt.getObject().isResource() || stmt.getObject().isAnon()) {
                continue;
            }

            String entityUri = stmt.getSubject().getURI();
            String directTypeUri = stmt.getObject().asResource().getURI();

            if (isSystemType(directTypeUri)) continue;

            // Fetch or create the type set for this entity
            Set<String> allTypes = entityTypes.computeIfAbsent(entityUri, k -> new HashSet<>());

            allTypes.add(directTypeUri);

            // Add all inferred superclasses from our TBox map
            Set<String> supers = classHierarchy.get(directTypeUri);
            if (supers != null) {
                allTypes.addAll(supers);
            }
        }
    }

    // Helper to ignore generic OWL/RDF/RDFS types
    private boolean isSystemType(String uri) {
        if (uri == null) return false;
        
        return uri.equals(OWL.Thing.getURI()) ||
                uri.equals(RDFS.Resource.getURI()) ||
                uri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#") ||
                uri.startsWith("http://www.w3.org/2000/01/rdf-schema#") ||
                uri.startsWith("http://www.w3.org/2002/07/owl#");
    }

    // Getters for your custom pathfinder
    public Map<String, Set<String>> getEntityTypes() { return entityTypes; }
    public Map<String, PropertyConstraint> getPropertyConstraints() { return propertyConstraints; }
    public Map<String, Set<String>> getDisjointClasses() { return disjointClassesMap; }

}
