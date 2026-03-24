package evolveAggregation.evaluation;

import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RankingTree;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.rules.Rule;
import evolveAggregation.graphTools.SemanticGraphManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.Stream;

/**
 * The Evaluator class handles the performance evaluation of a rule-based grounding engine.
 * It computes standard Link Prediction metrics (Hits@K, MRR) and Semantic Consistency metrics (sem@K).
 *
 * Link Prediction evaluation typically uses a filtered setting where known true facts are skipped
 * when calculating the rank of a predicted correct entity.
 * Semantic Consistency evaluation checks if the top-K predictions satisfy ontological constraints
 * such as functionality, domain, and range.
 */
public class Evaluator {

    /** The engine used to ground rules and find candidate entities. Note, by inheritance this will also work with SemanticGroundingEngine*/
    private final GroundingEngine engine;
    /** The registry containing the rules to be evaluated. */
    private final RuleRegistry registry;
    /** A set of all known facts in the dataset, used for filtered link prediction. */
    private final Set<String> allKnownFacts;
    /** Manages ontological constraints (domain, range, functionality). Will always use the SemanticGraph as it stores the cosntraints for evaluation*/
    private final SemanticGraphManager semanticManager;

    /** Maps a 'subject\tpredicate' string to the number of known objects for that pair. */
    private final Map<String, Integer> knownObjectCounts = new HashMap<>();
    /** Maps a 'predicate\tobject' string to the number of known subjects for that pair. */
    private final Map<String, Integer> knownSubjectCounts = new HashMap<>();

    /**
     * Constructs an Evaluator with the necessary components.
     *
     * @param engine The grounding engine to apply rules.
     * @param registry The rule registry containing rules for evaluation.
     * @param allKnownFacts All known triples in the graph (train/valid/test).
     * @param semanticManager The manager for semantic constraints.
     */
    public Evaluator(GroundingEngine engine, RuleRegistry registry, Set<String> allKnownFacts, SemanticGraphManager semanticManager) {
        this.engine = engine;
        this.registry = registry;
        this.allKnownFacts = allKnownFacts;
        this.semanticManager = semanticManager;
        buildFactIndexes();
    }

    /**
     * Intermediate result structure for a single link prediction task.
     */
    private static class RankResult {
        /** The filtered rank of the correct entity. -1 if not found within maxRanks. */
        int hitRank = -1;
        /** Number of semantically consistent predictions at top 1. */
        int consistentAt1 = 0;
        /** Number of semantically consistent predictions at top 5. */
        int consistentAt5 = 0;
        /** Number of semantically consistent predictions at top 10. */
        int consistentAt10 = 0;
        /** Total predictions considered at top 1 (usually 1 if available). */
        int totalAt1 = 0;
        /** Total predictions considered at top 5 (up to 5). */
        int totalAt5 = 0;
        /** Total predictions considered at top 10 (up to 10). */
        int totalAt10 = 0;
    }

    /**
     * Indexes the known facts to quickly count subjects and objects for given predicate-entity pairs.
     * This is used to determine how many predictions to generate before giving up.
     */
    private void buildFactIndexes() {
        for (String fact : allKnownFacts) {
            String[] parts = fact.split("\\t");
            if (parts.length >= 3) {
                String subPred = parts[0] + "\t" + parts[1];
                String predObj = parts[1] + "\t" + parts[2];

                knownObjectCounts.put(subPred, knownObjectCounts.getOrDefault(subPred, 0) + 1);
                knownSubjectCounts.put(predObj, knownSubjectCounts.getOrDefault(predObj, 0) + 1);
            }
        }
    }

    /**
     * Calculates Hits@K and Semantic Consistency metrics for a single prediction task.
     *
     * @param predictions Map of candidate entities to their collected rule confidences.
     * @param sourceEntity The entity from which the prediction starts.
     * @param predicate The relation being predicted.
     * @param correctEntity The ground truth entity.
     * @param predictingObject True if we are predicting the object (s, p, ?), false for subject (?, p, o).
     * @param maxRanks The maximum rank to consider for Link Prediction.
     * @param semanticManager Manager for checking semantic constraints.
     * @return A RankResult containing the hit rank and consistency counts.
     */
    private RankResult calculateMetrics(Map<String, TreeSet<Float>> predictions, String sourceEntity,
                                        String predicate, String correctEntity, boolean predictingObject,
                                        int maxRanks, SemanticGraphManager semanticManager) {

        RankResult result = new RankResult();
        if (predictions.isEmpty()) return result;

        RankingTree tree = new RankingTree();
        List<RankingTree.Candidate> sortedCandidates = tree.getFinalRanking(predictions);

        int filteredRank = 1;
        int rawRank = 1;

        boolean isFunc = predictingObject ? semanticManager.isFunctional(predicate) : semanticManager.isInverseFunctional(predicate);

        String lookupKey = predictingObject ? sourceEntity + "\t" + predicate : predicate + "\t" + sourceEntity;
        boolean alreadyHasValue = predictingObject ?
                knownObjectCounts.getOrDefault(lookupKey, 0) > 0 :
                knownSubjectCounts.getOrDefault(lookupKey, 0) > 0;

        for (RankingTree.Candidate candidate : sortedCandidates) {
            String predictedEntity = candidate.entity;

        // --- 1. SEMANTIC CONSISTENCY CHECK (sem@N) ---
            // We check consistency for the top 10 raw predictions.
            if (rawRank <= 10) {
                boolean isConsistent = true;

                // Check functionality: if relation is functional and a value already exists,
                // any other predicted value (except the correct one) is inconsistent.
                if (isFunc && alreadyHasValue && !predictedEntity.equals(correctEntity)) {
                    isConsistent = false;
                }

                if (isConsistent) {
                    // Check domain and range constraints based on the prediction direction.
                    if (predictingObject) {
                        if (semanticManager.violatesRange(predictedEntity, predicate)) isConsistent = false;
                    } else {
                        if (semanticManager.violatesDomain(predictedEntity, predicate)) isConsistent = false;
                    }
                }

                // Accumulate consistency counts for sem@1, sem@5, and sem@10
                if (rawRank <= 1) { result.totalAt1++; if (isConsistent) result.consistentAt1++; }
                if (rawRank <= 5) { result.totalAt5++; if (isConsistent) result.consistentAt5++; }
                if (rawRank <= 10) { result.totalAt10++; if (isConsistent) result.consistentAt10++; }
            }

            // --- 2. STANDARD LINK PREDICTION CHECK (Hits@K) ---
            // Filtered setting: skip known true facts that are not the target correct entity.
            if (filteredRank <= maxRanks && result.hitRank == -1) {
                if (predictedEntity.equals(correctEntity)) {
                    result.hitRank = filteredRank;
                } else {
                    String factString = predictingObject
                            ? sourceEntity + "\t" + predicate + "\t" + predictedEntity
                            : predictedEntity + "\t" + predicate + "\t" + sourceEntity;

                    // If the predicted fact is already known but not the one we are looking for,
                    // we skip it (it doesn't increase the rank).
                    if (!allKnownFacts.contains(factString)) {
                        filteredRank++;
                    }
                }
            }

            rawRank++;
            if (result.hitRank != -1 && rawRank > 10) break;
            if (filteredRank > maxRanks && rawRank > 10) break;
        }

        return result;
    }

    /**
     * Evaluates the rules on a test dataset.
     * For each triple (s, p, o) in the test set, it performs both:
     * 1. Forward prediction: (s, p, ?) to find 'o'.
     * 2. Backward prediction: (?, p, o) to find 's'.
     *
     * @param testPath Path to the TSV file containing test triples.
     * @param limitN Maximum number of test triples to process.
     * @return A Metrics object containing the aggregated results.
     */
    public Metrics evaluate(String testPath, int limitN) {
        AtomicInteger hits1 = new AtomicInteger(0);
        AtomicInteger hits5 = new AtomicInteger(0);
        AtomicInteger hits10 = new AtomicInteger(0);

        // FIXED: Changed to DoubleAdder
        DoubleAdder sem1 = new DoubleAdder();
        DoubleAdder sem5 = new DoubleAdder();
        DoubleAdder sem10 = new DoubleAdder();
        DoubleAdder mrr = new DoubleAdder();
        AtomicInteger totalPredictions = new AtomicInteger(0);

        AtomicInteger processedLines = new AtomicInteger(0);
        int K_RANKS = 100;

        try (Stream<String> lines = Files.lines(Paths.get(testPath))) {
            lines.limit(limitN)
                    .parallel()
                    .forEach(line -> {
                        String[] parts = line.split("\\s+");
                        if (parts.length < 3) return;

                        String subject = parts[0];
                        String predicate = parts[1];
                        String object = parts[2];

                        List<Rule> candidateRules = new ArrayList<>(registry.getPredictingRules(predicate));
                        candidateRules.sort((r1, r2) -> Float.compare(r2.getConfidence(), r1.getConfidence()));

                        // --- Object prediction (Forward: subject + predicate -> ?) ---
                        Map<String, TreeSet<Float>> objectPredictions = new HashMap<>();
                        String subPredKey = subject + "\t" + predicate;
                        // Determine how many predictions to collect based on K_RANKS and already known objects.
                        int objectStopThreshold = K_RANKS + knownObjectCounts.getOrDefault(subPredKey, 0);

                        for (Rule r : candidateRules) {
                            r.apply(engine, true, subject, predicate, objectPredictions);
                            if (objectPredictions.size() >= objectStopThreshold) break;
                        }

                        // Calculate metrics for the forward prediction.
                        RankResult objectResult = calculateMetrics(objectPredictions, subject, predicate, object, true, K_RANKS, semanticManager);
                        if (objectResult.hitRank != -1) {
                            if (objectResult.hitRank <= 1) hits1.incrementAndGet();
                            if (objectResult.hitRank <= 5) hits5.incrementAndGet();
                            if (objectResult.hitRank <= 10) hits10.incrementAndGet();
                            mrr.add(1.0 / objectResult.hitRank);
                        }
                        // Accumulate consistency results (sem@K)
                        if (objectResult.totalAt1 > 0) sem1.add((double) objectResult.consistentAt1 / objectResult.totalAt1);
                        if (objectResult.totalAt5 > 0) sem5.add((double) objectResult.consistentAt5 / objectResult.totalAt5);
                        if (objectResult.totalAt10 > 0) sem10.add((double) objectResult.consistentAt10 / objectResult.totalAt10);
                        totalPredictions.incrementAndGet();

                        // --- Subject prediction (Backward: ? + predicate -> object) ---
                        Map<String, TreeSet<Float>> subjectPredictions = new HashMap<>();
                        String predObjKey = predicate + "\t" + object;
                        int subjectStopThreshold = K_RANKS + knownSubjectCounts.getOrDefault(predObjKey, 0);

                        for (Rule r : candidateRules) {
                            r.apply(engine, false, object, predicate, subjectPredictions);
                            if (subjectPredictions.size() >= subjectStopThreshold) break;
                        }

                        // Calculate metrics for the backward prediction.
                        RankResult subjectResult = calculateMetrics(subjectPredictions, subject, predicate, object, false, K_RANKS, semanticManager);
                        if (subjectResult.hitRank != -1) {
                            if (subjectResult.hitRank <= 1) hits1.incrementAndGet();
                            if (subjectResult.hitRank <= 5) hits5.incrementAndGet();
                            if (subjectResult.hitRank <= 10) hits10.incrementAndGet();
                            mrr.add(1.0 / subjectResult.hitRank);
                        }
                        if (subjectResult.totalAt1 > 0) sem1.add((double) subjectResult.consistentAt1 / subjectResult.totalAt1);
                        if (subjectResult.totalAt5 > 0) sem5.add((double) subjectResult.consistentAt5 / subjectResult.totalAt5);
                        if (subjectResult.totalAt10 > 0) sem10.add((double) subjectResult.consistentAt10 / subjectResult.totalAt10);
                        totalPredictions.incrementAndGet();

                        int currentProgress = processedLines.incrementAndGet();
                        if (currentProgress % 1000 == 0) {
                            System.out.println("Processed " + currentProgress + " test facts...");
                        }
                    });

        } catch (IOException e) {
            e.printStackTrace();
        }

        int total = totalPredictions.get();
        if (total > 0) {
            double h1 = (double) hits1.get() / total;
            double h5 = (double) hits5.get() / total;
            double h10 = (double) hits10.get() / total;
            double finalMrr = mrr.sum() / total;

            // Extract the final semantic ratios
            double s1 = sem1.sum() / total;
            double s5 = sem5.sum() / total;
            double s10 = sem10.sum() / total;

            System.out.printf("  Evaluated %d total predictions across %d facts.\n", total, processedLines.get());

            // UPDATED: Assuming you expand your Metrics class to hold these
            // You will need to add these fields to your Metrics constructor!
            return new Metrics(h1, h5, h10, finalMrr, s1, s5, s10, total);
        }

        // Return empty if no predictions
        return new Metrics(0, 0, 0, 0, 0, 0, 0, 0);
    }
}