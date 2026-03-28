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
    /** A set of facts known during training, used for functional consistency checks. */
    private final Set<String> trainKnownFacts;
    /** Manages ontological constraints (domain, range, functionality). Will always use the SemanticGraph as it stores the cosntraints for evaluation*/
    private final SemanticGraphManager semanticManager;

    /** Maps a 'subject\tpredicate' string to the number of known objects for that pair. */
    private final Map<String, Integer> knownObjectCounts = new HashMap<>();
    /** Maps a 'predicate\tobject' string to the number of known subjects for that pair. */
    private final Map<String, Integer> knownSubjectCounts = new HashMap<>();
    /** Maps a 'subject\tpredicate' string to the number of known objects for that pair. */
    private final Map<String, Integer> knownTrainObjectCounts = new HashMap<>();
    /** Maps a 'predicate\tobject' string to the number of known subjects for that pair. */
    private final Map<String, Integer> knownTrainSubjectCounts = new HashMap<>();
    /**
     * Constructs an Evaluator with the necessary components.
     *
     * @param engine The grounding engine to apply rules.
     * @param registry The rule registry containing rules for evaluation.
     * @param allKnownFacts All known triples in the graph (train/valid/test).
     * @param trainKnownFacts The triples known during training (train).
     * @param semanticManager The manager for semantic constraints.
     */
    public Evaluator(GroundingEngine engine, RuleRegistry registry, Set<String> allKnownFacts, Set<String> trainKnownFacts, SemanticGraphManager semanticManager) {
        this.engine = engine;
        this.registry = registry;
        this.allKnownFacts = allKnownFacts;
        this.trainKnownFacts = trainKnownFacts;
        this.semanticManager = semanticManager;
        buildFactIndexes();
    }

    /**
     * Intermediate result structure for a single link prediction task.
     */
    private static class RankResult {
        double averageRank = -1.0;

        int consistentAt1 = 0;
        int consistentAt5 = 0;
        int consistentAt10 = 0;
        int totalAt1 = 0;
        int totalAt5 = 0;
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
        for (String fact : trainKnownFacts) {
            String[] parts = fact.split("\\t");
            if (parts.length >= 3) {
                String subPred = parts[0] + "\t" + parts[1];
                String predObj = parts[1] + "\t" + parts[2];

                knownTrainObjectCounts.put(subPred, knownTrainObjectCounts.getOrDefault(subPred, 0) + 1);
                knownTrainSubjectCounts.put(predObj, knownTrainSubjectCounts.getOrDefault(predObj, 0) + 1);
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
    private RankResult calculateMetrics(Map<String, List<Float>> predictions, String sourceEntity,
                                        String predicate, String correctEntity, boolean predictingObject,
                                        int maxRanks, SemanticGraphManager semanticManager) {

        RankResult result = new RankResult();
        if (predictions.isEmpty()) return result;

        RankingTree tree = new RankingTree();
        List<RankingTree.Candidate> sortedCandidates = tree.getFinalRanking(predictions);

        int filteredRank = 1;
        int rawRank = 1;

        int currentFilteredRank = 1;
        int tieBlockStartRank = 1;
        int entitiesInTieBlock = 0;
        boolean targetInBlock = false;
        List<Float> currentTieScore = null;

        boolean isFunc = predictingObject ? semanticManager.isFunctional(predicate) : semanticManager.isInverseFunctional(predicate);
        String lookupKey = predictingObject ? sourceEntity + "\t" + predicate : predicate + "\t" + sourceEntity;
//        boolean alreadyHasValue = predictingObject ?
//                knownObjectCounts.getOrDefault(lookupKey, 0) > 0 :
//                knownSubjectCounts.getOrDefault(lookupKey, 0) > 0;
        boolean alreadyHasValue = predictingObject ?
                knownTrainObjectCounts.getOrDefault(lookupKey, 0) > 0 :
                knownTrainSubjectCounts.getOrDefault(lookupKey, 0) > 0;

        for (RankingTree.Candidate candidate : sortedCandidates) {
            String predictedEntity = candidate.entity;
            String factString = predictingObject
                    ? sourceEntity + "\t" + predicate + "\t" + predictedEntity
                    : predictedEntity + "\t" + predicate + "\t" + sourceEntity;

            // --- 1. SEMANTIC CONSISTENCY CHECK (sem@N) ---
            if (rawRank <= 10) {
                boolean isConsistent = true;
                if (isFunc && alreadyHasValue && !trainKnownFacts.contains(factString)) {
                    isConsistent = false;
                }
                if (isConsistent) {
                    if (predictingObject) {
                        if (semanticManager.violatesRange(predictedEntity, predicate)) isConsistent = false;
                    } else {
                        if (semanticManager.violatesDomain(predictedEntity, predicate)) isConsistent = false;
                    }
                }
                if (rawRank <= 1) { result.totalAt1++; if (isConsistent) result.consistentAt1++; }
                if (rawRank <= 5) { result.totalAt5++; if (isConsistent) result.consistentAt5++; }
                if (rawRank <= 10) { result.totalAt10++; if (isConsistent) result.consistentAt10++; }
            }
            rawRank++;

            // --- 2. TIE-AWARE LINK PREDICTION (Hits@K, MRR) ---
            // FILTERING: Skip known true facts to prevent data leakage.
            if (!predictedEntity.equals(correctEntity) && allKnownFacts.contains(factString)) {
                continue; // Do not advance the filtered rank for this known fact
            }

            // TIE DETECTION: Check if we are starting a new block of identical scores
            if (currentTieScore == null || !candidate.confidences.equals(currentTieScore)) {
                if (targetInBlock) break; // We found the target in the previous tie block! Stop searching.

                currentTieScore = candidate.confidences;
                tieBlockStartRank = currentFilteredRank;
                entitiesInTieBlock = 0;
            }

            entitiesInTieBlock++;
            currentFilteredRank++;

            if (predictedEntity.equals(correctEntity)) {
                targetInBlock = true;
            }

            // Early stopping if we've passed maxRanks and we aren't resolving a tie block
            if (currentFilteredRank > maxRanks && !targetInBlock && !candidate.confidences.equals(currentTieScore)) {
                break;
            }
        }

        // --- 3. CALCULATE AVERAGE RANK FOR THE TARGET ---
        if (targetInBlock) {
            int tieBlockEndRank = tieBlockStartRank + entitiesInTieBlock - 1;
            result.averageRank = (tieBlockStartRank + tieBlockEndRank) / 2.0;
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
        DoubleAdder mrr = new DoubleAdder();

        // FIXED: Changed to DoubleAdder
        DoubleAdder sem1 = new DoubleAdder();
        DoubleAdder sem5 = new DoubleAdder();
        DoubleAdder sem10 = new DoubleAdder();
        AtomicInteger semQueriesAt1 = new AtomicInteger(0);
        AtomicInteger semQueriesAt5 = new AtomicInteger(0);
        AtomicInteger semQueriesAt10 = new AtomicInteger(0);
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
                        Map<String, List<Float>> objectPredictions = new HashMap<>();
                        String subPredKey = subject + "\t" + predicate;
                        // Determine how many predictions to collect based on K_RANKS and already known objects.
                        int objectStopThreshold = K_RANKS + knownObjectCounts.getOrDefault(subPredKey, 0);

                        for (Rule r : candidateRules) {
                            r.apply(engine, true, subject, predicate, objectPredictions);
                            if (objectPredictions.size() >= objectStopThreshold) break;
                        }

                        // Calculate metrics for the forward prediction.
                        RankResult objectResult = calculateMetrics(objectPredictions, subject, predicate, object, true, K_RANKS, semanticManager);

                        // Check if averageRank is > 0 (meaning it was successfully predicted)
                        if (objectResult.averageRank > 0) {
                            if (objectResult.averageRank <= 1.0) hits1.incrementAndGet();
                            if (objectResult.averageRank <= 5.0) hits5.incrementAndGet();
                            if (objectResult.averageRank <= 10.0) hits10.incrementAndGet();
                            mrr.add(1.0 / objectResult.averageRank);
                        }
                        // Accumulate consistency results (sem@K)
                        if (objectResult.totalAt1 > 0) {
                            sem1.add((double) objectResult.consistentAt1 / objectResult.totalAt1);
                            semQueriesAt1.incrementAndGet();
                        }
                        if (objectResult.totalAt5 > 0) {
                            sem5.add((double) objectResult.consistentAt5 / objectResult.totalAt5);
                            semQueriesAt5.incrementAndGet();
                        }
                        if (objectResult.totalAt10 > 0) {
                            sem10.add((double) objectResult.consistentAt10 / objectResult.totalAt10);
                            semQueriesAt10.incrementAndGet();
                        }
                        totalPredictions.incrementAndGet();

                        // --- Subject prediction (Backward: ? + predicate -> object) ---
                        Map<String, List<Float>> subjectPredictions = new HashMap<>();
                        String predObjKey = predicate + "\t" + object;
                        int subjectStopThreshold = K_RANKS + knownSubjectCounts.getOrDefault(predObjKey, 0);

                        for (Rule r : candidateRules) {
                            r.apply(engine, false, object, predicate, subjectPredictions);
                            if (subjectPredictions.size() >= subjectStopThreshold) break;
                        }

                        // Calculate metrics for the backward prediction.
                        RankResult subjectResult = calculateMetrics(objectPredictions, subject, predicate, object, true, K_RANKS, semanticManager);

                        // Check if averageRank is > 0 (meaning it was successfully predicted)
                        if (subjectResult.averageRank > 0) {
                            if (subjectResult.averageRank <= 1.0) hits1.incrementAndGet();
                            if (subjectResult.averageRank <= 5.0) hits5.incrementAndGet();
                            if (subjectResult.averageRank <= 10.0) hits10.incrementAndGet();
                            mrr.add(1.0 / subjectResult.averageRank);
                        }
                        if (subjectResult.totalAt1 > 0) {
                            sem1.add((double) subjectResult.consistentAt1 / subjectResult.totalAt1);
                            semQueriesAt1.incrementAndGet();
                        }
                        if (subjectResult.totalAt5 > 0) {
                            sem5.add((double) subjectResult.consistentAt5 / subjectResult.totalAt5);
                            semQueriesAt5.incrementAndGet();
                        }
                        if (subjectResult.totalAt10 > 0) {
                            sem10.add((double) subjectResult.consistentAt10 / subjectResult.totalAt10);
                            semQueriesAt10.incrementAndGet();
                        }
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

            double s1 = semQueriesAt1.get() > 0 ? sem1.sum() / semQueriesAt1.get() : 0.0;
            double s5 = semQueriesAt5.get() > 0 ? sem5.sum() / semQueriesAt5.get() : 0.0;
            double s10 = semQueriesAt10.get() > 0 ? sem10.sum() / semQueriesAt10.get() : 0.0;

            System.out.printf("  Evaluated %d total predictions across %d facts.\n", total, processedLines.get());

            return new Metrics(h1, h5, h10, finalMrr, s1, s5, s10, total);
        }

        // Return empty if no predictions
        return new Metrics(0, 0, 0, 0, 0, 0, 0, 0);
    }

//    public double getFilteredAverageRank(List<RankingTree.Candidate> rankedResults, String targetEntity, Set<String> knownTrueEntities) {
//        int currentRank = 1;
//        int tieBlockStartRank = 1;
//        int entitiesInTieBlock = 0;
//        boolean targetInBlock = false;
//        List<Float> currentTieScore = null;
//
//        for (RankingTree.Candidate c : rankedResults) {
//            // 1. FILTERING: Skip known true facts to prevent data leakage.
//            // If the candidate is a known fact, AND it is not our current target, it becomes invisible.
//            if (!c.entity.equals(targetEntity) && knownTrueEntities.contains(c.entity)) {
//                continue;
//            }
//
//            // 2. TIE DETECTION: Check if we are starting a new block of identical scores
//            if (currentTieScore == null || !c.confidences.equals(currentTieScore)) {
//                // If we found our target in the PREVIOUS block, stop searching.
//                if (targetInBlock) {
//                    break;
//                }
//                // Otherwise, reset the block trackers for this new score
//                currentTieScore = c.confidences;
//                tieBlockStartRank = currentRank;
//                entitiesInTieBlock = 0;
//            }
//
//            // 3. COUNTING: Add this entity to the current block's footprint
//            entitiesInTieBlock++;
//            currentRank++; // Advance the rank counter for the next distinct score block
//
//            if (c.entity.equals(targetEntity)) {
//                targetInBlock = true;
//            }
//        }
//
//        // 4. CALCULATION: Apply the average rank formula
//        if (targetInBlock) {
//            int tieBlockEndRank = tieBlockStartRank + entitiesInTieBlock - 1;
//            return (tieBlockStartRank + tieBlockEndRank) / 2.0;
//        }
//
//        // Target was not generated by any rules
//        return -1.0;
//    }
}