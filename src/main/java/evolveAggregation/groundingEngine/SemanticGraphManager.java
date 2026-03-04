package evolveAggregation.groundingEngine;

import evolveAggregation.optimizedGraph.GraphDictionary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SemanticGraphManager extends GraphManager{
    private SemanticConstraintLoader scl;
    private final GraphDictionary typeDict;
    private final Map<Integer, Set<Integer>> entityIdTypes;
    private final Map<Integer, IntPropertyConstraint> propertyIdConstraints;

    public SemanticGraphManager() {
        super();
        this.scl = new SemanticConstraintLoader();
        this.typeDict = new GraphDictionary();
        this.entityIdTypes = new HashMap<>();
        this.propertyIdConstraints = new HashMap<>();
    }

    public static class IntPropertyConstraint {
        public Set<Integer> domainClasses = new HashSet<>();
        public Set<Integer> rangeClasses = new HashSet<>();
        public Set<Integer> disjointProperties = new HashSet<>();
        public boolean isFunctional = false;
        public boolean isSymmetric = false;
        public boolean isTransitive = false;
        public boolean isInverseFunctional = false;
    }


    public void compileConstraints(String ontologyPath) {
        compileConstraints(ontologyPath, null);
    }

    public void compileConstraints(String tboxPath, String aboxPath) {
        if (this.getGraph().isEmpty()) {
            throw new RuntimeException("Graph is empty, cannot compile constraints.");
        }

        scl.loadAndExtract(tboxPath, aboxPath);

        Map<String, SemanticConstraintLoader.PropertyConstraint> rawProps = scl.getPropertyConstraints();

        for (Map.Entry<String, SemanticConstraintLoader.PropertyConstraint> entry : rawProps.entrySet()) {
            // Get the integer ID for the property URI
            int relId = getRelationDict().getId(entry.getKey());

            SemanticConstraintLoader.PropertyConstraint rawConstraint = entry.getValue();
            IntPropertyConstraint fastConstraint = new IntPropertyConstraint();

            // Copy the boolean flags directly
            fastConstraint.isFunctional = rawConstraint.isFunctional;
            fastConstraint.isSymmetric = rawConstraint.isSymmetric;
            fastConstraint.isTransitive = rawConstraint.isTransitive;
            fastConstraint.isInverseFunctional = rawConstraint.isInverseFunctional;

            // Translate Domain Strings to Domain Ints
            for (String domainUri : rawConstraint.domainClasses) {
                fastConstraint.domainClasses.add(getEntityDict().getId(domainUri));
            }

            // Translate Range Strings to Range Ints
            for (String rangeUri : rawConstraint.rangeClasses) {
                fastConstraint.rangeClasses.add(getEntityDict().getId(rangeUri));
            }

            // Translate Disjoint Properties Strings to Ints
            for (String disjointUri : rawConstraint.disjointProperties) {
                fastConstraint.disjointProperties.add(getRelationDict().getId(disjointUri));
            }

            propertyIdConstraints.put(relId, fastConstraint);
        }

        Map<String, Set<String>> rawTypes = scl.getEntityTypes();

        for (Map.Entry<String, Set<String>> entry : rawTypes.entrySet()) {
            // The instance belongs to the base entityDict
            int entityId = getEntityDict().getId(entry.getKey());
            Set<Integer> typeIds = new HashSet<>();

            // The classes belong to the semantic typeDict
            for (String typeUri : entry.getValue()) {
                typeIds.add(typeDict.getId(typeUri));
            }

            entityIdTypes.put(entityId, typeIds);
        }
    }
}
