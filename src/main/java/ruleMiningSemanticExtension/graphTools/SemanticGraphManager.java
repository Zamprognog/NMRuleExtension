package ruleMiningSemanticExtension.graphTools;

import java.util.*;

public class SemanticGraphManager extends GraphManager {
    private SemanticConstraintLoader scl;
    private final GraphDictionary typeDict;
    public Map<Integer, Set<Integer>> getEntityIdTypes() {
        return entityIdTypes;
    }
    public Map<Integer, IntPropertyConstraint> getPropertyIdConstraints() {
        return propertyIdConstraints;
    }

    private final Map<Integer, Set<Integer>> entityIdTypes;
    private final Map<Integer, IntPropertyConstraint> propertyIdConstraints;
    private final Map<Integer, Set<Integer>> disjointClasses;

    public SemanticGraphManager() {
        super();
        this.scl = new SemanticConstraintLoader();
        this.typeDict = new GraphDictionary();
        this.entityIdTypes = new HashMap<>();
        this.propertyIdConstraints = new HashMap<>();
        this.disjointClasses = new HashMap<>();
    }

    public Map<Integer, Set<Integer>> getDisjointClasses() {
        return disjointClasses;
    }

    public static class IntPropertyConstraint {
        public Set<Integer> domainClasses = new HashSet<>();
        public Set<Integer> rangeClasses = new HashSet<>();
        public Set<Integer> disjointProperties = new HashSet<>();

        public Set<Integer> disjointWithDomain = new HashSet<>();
        public Set<Integer> disjointWithRange = new HashSet<>();

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
        System.out.println("SemanticGraphManager: Compiled " + rawProps.size() + " property constraints from TBox.");

        for (Map.Entry<String, SemanticConstraintLoader.PropertyConstraint> entry : rawProps.entrySet()) {
            // Get the integer ID for the property URI
            int relId = getRelationDict().getId(entry.getKey());

            SemanticConstraintLoader.PropertyConstraint rawConstraint = entry.getValue();
            IntPropertyConstraint encodedConstraint = new IntPropertyConstraint();

            // Copy the boolean flags directly
            encodedConstraint.isFunctional = rawConstraint.isFunctional;
            encodedConstraint.isSymmetric = rawConstraint.isSymmetric;
            encodedConstraint.isTransitive = rawConstraint.isTransitive;
            encodedConstraint.isInverseFunctional = rawConstraint.isInverseFunctional;

            // Translate Domain Strings to Domain Ints
            for (String domainUri : rawConstraint.domainClasses) {
                encodedConstraint.domainClasses.add(getTypeDict().getId(domainUri));
            }

            // Translate Range Strings to Range Ints
            for (String rangeUri : rawConstraint.rangeClasses) {
                encodedConstraint.rangeClasses.add(getTypeDict().getId(rangeUri));
            }

            // Translate Disjoint Properties Strings to Ints
            for (String disjointUri : rawConstraint.disjointProperties) {
                // FIX: Use Relation Dict for properties, not Entity Dict
                encodedConstraint.disjointProperties.add(getRelationDict().getId(disjointUri));
            }

            propertyIdConstraints.put(relId, encodedConstraint);
        }

        Map<String, Set<String>> rawDisjointClasses = scl.getDisjointClasses();
        System.out.println("SemanticGraphManager: Extracted " + rawDisjointClasses.size() + " disjoint class mappings.");
        for (Map.Entry<String, Set<String>> entry : rawDisjointClasses.entrySet()) {
            int classId = getTypeDict().getId(entry.getKey());
            Set<Integer> encodedDisjoints = new HashSet<>();

            for (String disjointUri : entry.getValue()) {
                encodedDisjoints.add(getTypeDict().getId(disjointUri));
            }
            disjointClasses.put(classId, encodedDisjoints);
        }

        Map<String, Set<String>> rawTypes = scl.getEntityTypes();
        System.out.println("SemanticGraphManager: Loaded types for " + rawTypes.size() + " entities.");

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

    /**
     * Checks if a predicate is explicitly defined as Functional.
     * (e.g., a person can only have ONE birth date)
     */
    public boolean isFunctional(String predicate) {
        int predId = getRelationDict().lookup(predicate);
        if (predId == -1) return false;

        IntPropertyConstraint constraint = propertyIdConstraints.get(predId);
        return constraint != null && constraint.isFunctional;
    }

    /**
     * Checks if a predicate is explicitly defined as Inverse Functional.
     * (e.g., a social security number can only belong to ONE person)
     */
    public boolean isInverseFunctional(String predicate) {
        int predId = getRelationDict().lookup(predicate);
        if (predId == -1) return false;

        IntPropertyConstraint constraint = propertyIdConstraints.get(predId);
        return constraint != null && constraint.isInverseFunctional;
    }

    /**
     * Checks if a candidate object already has an incoming subject for the predicate
     * (other than anchorSubject), which would violate inverse functionality.
     * Used when predicting the object: (anchorSubject, predicate, candidateObject).
     */
    public boolean violatesInverseFunctionalityAsObject(String candidateObject, String predicate, String anchorSubject) {
        int relId = getRelationDict().lookup(predicate);
        if (relId == -1) return false;
        IntPropertyConstraint constraint = propertyIdConstraints.get(relId);
        if (constraint == null || !constraint.isInverseFunctional) return false;

        int objId = getEntityDict().lookup(candidateObject);
        if (objId == -1) return false;

        int[] existingSubjects = getGraph().getBackwardTargets(objId, relId);
        if (existingSubjects == null || existingSubjects.length == 0) return false;

        int anchorId = getEntityDict().lookup(anchorSubject);
        for (int sid : existingSubjects) {
            if (sid != anchorId) return true;
        }
        return false;
    }

    /**
     * Checks if a candidate subject already has an outgoing object for the predicate
     * (other than anchorObject), which would violate functionality.
     * Used when predicting the subject: (candidateSubject, predicate, anchorObject).
     */
    public boolean violatesFunctionalityAsSubject(String candidateSubject, String predicate, String anchorObject) {
        int relId = getRelationDict().lookup(predicate);
        if (relId == -1) return false;
        IntPropertyConstraint constraint = propertyIdConstraints.get(relId);
        if (constraint == null || !constraint.isFunctional) return false;

        int subjectId = getEntityDict().lookup(candidateSubject);
        if (subjectId == -1) return false;

        int[] existingObjects = getGraph().getForwardTargets(subjectId, relId);
        if (existingObjects == null || existingObjects.length == 0) return false;

        int anchorId = getEntityDict().lookup(anchorObject);
        for (int oid : existingObjects) {
            if (oid != anchorId) return true;
        }
        return false;
    }

    /**
     * Checks if the predicted entity violates the Domain constraint of the predicate.
     * (Used when predicting the Subject backwards)
     */
    public boolean violatesDomain(String entityStr, String predicateStr) {
        int entId = getEntityDict().lookup(entityStr);
        int predId = getRelationDict().lookup(predicateStr);

        // If we don't know the entity or predicate, we can't definitively say it violates the constraint (Open World Assumption)
        if (entId == -1 || predId == -1) return false;

        IntPropertyConstraint constraint = propertyIdConstraints.get(predId);
        if (constraint == null || constraint.disjointWithDomain.isEmpty()) return false; // No domain restricted

        Set<Integer> entTypes = entityIdTypes.get(entId);
        if (entTypes == null || entTypes.isEmpty()) return false; // Entity has no known types to conflict with

        return !Collections.disjoint(entTypes, constraint.disjointWithDomain);
    }

    /**
     * Checks if the predicted entity violates the Range constraint of the predicate.
     * (Used when predicting the Object forwards)
     */
    public boolean violatesRange(String entityStr, String predicateStr) {
        int entId = getEntityDict().lookup(entityStr);
        int predId = getRelationDict().lookup(predicateStr);

        if (entId == -1 || predId == -1) return false;

        IntPropertyConstraint constraint = propertyIdConstraints.get(predId);
        if (constraint == null || constraint.disjointWithRange.isEmpty()) return false;

        Set<Integer> entTypes = entityIdTypes.get(entId);
        if (entTypes == null || entTypes.isEmpty()) return false;

        return !Collections.disjoint(entTypes, constraint.disjointWithRange);
    }

    public void precomputeDisjointConstraints() {
        for (IntPropertyConstraint constraint : propertyIdConstraints.values()) {

            // 1. Precompute all classes disjoint with the required DOMAIN
            for (Integer domainClassId : constraint.domainClasses) {
                Set<Integer> disjointClassesForDomain = disjointClasses.get(domainClassId);
                if (disjointClassesForDomain != null) {
                    constraint.disjointWithDomain.addAll(disjointClassesForDomain);
                }
            }

            // 2. Precompute all classes disjoint with the required RANGE
            for (Integer rangeClassId : constraint.rangeClasses) {
                Set<Integer> disjointClassesForRange = disjointClasses.get(rangeClassId);
                if (disjointClassesForRange != null) {
                    constraint.disjointWithRange.addAll(disjointClassesForRange);
                }
            }
        }
    }

    public GraphDictionary getTypeDict() {
        return typeDict;
    }
}
