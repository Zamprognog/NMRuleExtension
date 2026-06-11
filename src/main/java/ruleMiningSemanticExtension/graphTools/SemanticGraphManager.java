package ruleMiningSemanticExtension.graphTools;

import java.util.*;
import java.util.LinkedList;

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
    /** Shortest hop distance from each type (by integer ID) to owl:Thing. */
    private Map<Integer, Integer> typeDepthMap = new HashMap<>();

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

        computeTypeDepths(scl.getDirectSuperClasses());
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

    /**
     * Checks if the entity's types match the domain classes of the predicate.
     * Returns true if there's at least one common type, or if no domain is defined.
     * Used for subject-prediction queries (?, p, o).
     */
    public boolean matchesDomain(String entityStr, String predicateStr) {
        int entId = getEntityDict().lookup(entityStr);
        int predId = getRelationDict().lookup(predicateStr);

        if (entId == -1 || predId == -1) return false;

        IntPropertyConstraint constraint = propertyIdConstraints.get(predId);
        if (constraint == null || constraint.domainClasses.isEmpty()) return true;

        Set<Integer> entTypes = entityIdTypes.get(entId);
        if (entTypes == null || entTypes.isEmpty()) return false;

        return !Collections.disjoint(entTypes, constraint.domainClasses);
    }

    /**
     * Checks if the entity's types match the range classes of the predicate.
     * Returns true if there's at least one common type, or if no range is defined.
     * Used for object-prediction queries (s, p, ?).
     */
    public boolean matchesRange(String entityStr, String predicateStr) {
        int entId = getEntityDict().lookup(entityStr);
        int predId = getRelationDict().lookup(predicateStr);

        if (entId == -1 || predId == -1) return false;

        IntPropertyConstraint constraint = propertyIdConstraints.get(predId);
        if (constraint == null || constraint.rangeClasses.isEmpty()) return true;

        Set<Integer> entTypes = entityIdTypes.get(entId);
        if (entTypes == null || entTypes.isEmpty()) return false;

        return !Collections.disjoint(entTypes, constraint.rangeClasses);
    }

    /**
     * BFS from the actual roots of the class hierarchy to assign each class its hop
     * distance from owl:Thing. Called from compileConstraints().
     *
     * We cannot anchor the BFS at owl:Thing directly because many datasets (e.g. YAGO4.5)
     * do not declare any explicit {@code subClassOf owl:Thing} edges — the TBox root is
     * something like {@code schema:Thing}. Instead we:
     * <ol>
     *   <li>Build the inverse (parent → children) map from directSuperClasses.</li>
     *   <li>Seed the BFS with owl:Thing itself (depth 0) PLUS any class whose every
     *       stated parent is either owl:Thing or absent from the TBox (i.e. a root
     *       class). Root classes are assigned depth 1.</li>
     *   <li>BFS outward, assigning depth = parent_depth + 1.</li>
     * </ol>
     * Classes that remain unreachable get no entry (so {@link #getMaxTypeDepthForEntity}
     * returns 0 for them).
     */
    private void computeTypeDepths(Map<String, Set<String>> directSuperClasses) {
        final String OWL_THING = "http://www.w3.org/2002/07/owl#Thing";

        // Build inverse map: parent → set of direct children
        Map<String, Set<String>> children = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : directSuperClasses.entrySet()) {
            String child = entry.getKey();
            for (String parent : entry.getValue()) {
                children.computeIfAbsent(parent, k -> new HashSet<>()).add(child);
            }
        }

        // Seed BFS: owl:Thing at depth 0, plus root classes (no parent in TBox) at depth 1
        Map<String, Integer> depths = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        depths.put(OWL_THING, 0);
        queue.add(OWL_THING);

        for (Map.Entry<String, Set<String>> entry : directSuperClasses.entrySet()) {
            String cls = entry.getKey();
            Set<String> parentSet = entry.getValue();
            // A root class has no parent, or every parent is either owl:Thing or
            // a URI that is not itself a key in directSuperClasses (external/unknown).
            boolean isRoot = parentSet.isEmpty()
                    || parentSet.stream().allMatch(
                            p -> OWL_THING.equals(p) || !directSuperClasses.containsKey(p));
            if (isRoot && !depths.containsKey(cls)) {
                depths.put(cls, 1);
                queue.add(cls);
            }
        }

        // BFS outward
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int d = depths.get(current);
            Set<String> childSet = children.get(current);
            if (childSet == null) continue;
            for (String child : childSet) {
                if (!depths.containsKey(child)) {
                    depths.put(child, d + 1);
                    queue.add(child);
                }
            }
        }

        // Translate URI → integer type IDs (owl:Thing itself is never in typeDict)
        typeDepthMap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : depths.entrySet()) {
            int typeId = getTypeDict().lookup(entry.getKey());
            if (typeId != -1) typeDepthMap.put(typeId, entry.getValue());
        }

        System.out.println("SemanticGraphManager: type depths computed for "
                + typeDepthMap.size() + " classes (BFS from hierarchy roots).");
    }

    /**
     * Returns the maximum hop distance from owl:Thing across all known types of the entity
     * (i.e. the depth of the most-specific type in the hierarchy).
     *
     * <p>We use max rather than min because {@code entityIdTypes} stores transitive
     * superclasses (needed for domain/range matching). Every entity therefore includes the
     * hierarchy root (e.g. {@code schema:Thing}, depth 1) in its type set, so min would
     * always return 1 — a constant that collapses to 0 after normalization. Max instead
     * reflects type specificity: entities typed to deep, narrow classes score higher.</p>
     *
     * <p>Entities with no type information return 0.</p>
     */
    public int getMaxTypeDepthForEntity(int entityId) {
        Set<Integer> types = entityIdTypes.get(entityId);
        if (types == null || types.isEmpty()) return 0;
        int max = 0;
        for (int typeId : types) {
            Integer d = typeDepthMap.get(typeId);
            if (d != null && d > max) max = d;
        }
        return max;
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
