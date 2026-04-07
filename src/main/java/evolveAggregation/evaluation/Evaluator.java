package evolveAggregation.evaluation;

import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RankingTree;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.rules.Rule;
import evolveAggregation.graphTools.SemanticGraphManager;

import evolveAggregation.utils.DualLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.Stream;

public class Evaluator {

    private final GroundingEngine engine;
    private final RuleRegistry registry;
    private final Set<String> allKnownFacts;
    private final Set<String> trainKnownFacts;
    private final SemanticGraphManager semanticManager;

    private final Map<String, Integer> knownObjectCounts = new HashMap<>();
    private final Map<String, Integer> knownSubjectCounts = new HashMap<>();
    private final Map<String, Integer> knownTrainObjectCounts = new HashMap<>();
    private final Map<String, Integer> knownTrainSubjectCounts = new HashMap<>();

    public Evaluator(GroundingEngine engine, RuleRegistry registry, Set<String> allKnownFacts, Set<String> trainKnownFacts, SemanticGraphManager semanticManager) {
        this.engine = engine;
        this.registry = registry;
        this.allKnownFacts = allKnownFacts;
        this.trainKnownFacts = trainKnownFacts;
        this.semanticManager = semanticManager;
        buildFactIndexes();
    }

    private static class RankResult {
        double mrrContribution = 0.0;
        double expectedHitsAt1 = 0.0;
        double expectedHitsAt5 = 0.0;
        double expectedHitsAt10 = 0.0;

        int consistentAt1 = 0;
        int consistentAt5 = 0;
        int consistentAt10 = 0;
        int consistentAt100 = 0;
        int totalAt1 = 0;
        int totalAt5 = 0;
        int totalAt10 = 0;
        int totalAt100 = 0;

        List<RankingTree.Candidate> filteredCandidates = new ArrayList<>();
    }

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

    // Helper to calculate expected hits for a tie block crossing a threshold
    private double calculateExpectedHits(int startRank, int endRank, int k) {
        if (endRank <= k) return 1.0;
        if (startRank > k) return 0.0;

        // Straddles the boundary: fraction of the block inside Top K
        int ranksInK = k - startRank + 1;
        int totalRanks = endRank - startRank + 1;
        return (double) ranksInK / totalRanks;
    }

    private RankResult calculateMetrics(Map<String, List<Float>> predictions, String sourceEntity,
                                        String predicate, String correctEntity, boolean predictingObject,
                                        int maxRanks, SemanticGraphManager semanticManager, boolean recordCandidates) {

        RankResult result = new RankResult();
        if (predictions.isEmpty()) return result;

        RankingTree tree = new RankingTree();
        List<RankingTree.Candidate> sortedCandidates = tree.getFinalRanking(predictions);

        int rawRank = 1;
        int currentFilteredRank = 1;
        int tieBlockStartRank = 1;
        int entitiesInTieBlock = 0;
        boolean targetInBlock = false;
        List<Float> currentTieScore = null;

        boolean isFunc = predictingObject ? semanticManager.isFunctional(predicate) : semanticManager.isInverseFunctional(predicate);
//        boolean isFunc = (predictingObject && semanticManager.isFunctional(predicate)); //todo:double check
        String lookupKey = predictingObject ? sourceEntity + "\t" + predicate : predicate + "\t" + sourceEntity;
        boolean alreadyHasValue = predictingObject ?
                knownTrainObjectCounts.getOrDefault(lookupKey, 0) > 0 :
                knownTrainSubjectCounts.getOrDefault(lookupKey, 0) > 0;

        for (RankingTree.Candidate candidate : sortedCandidates) {
            String predictedEntity = candidate.entity;
            String factString = predictingObject
                    ? sourceEntity + "\t" + predicate + "\t" + predictedEntity
                    : predictedEntity + "\t" + predicate + "\t" + sourceEntity;

            // --- 1. SEMANTIC CONSISTENCY CHECK ---
            if (rawRank <= 100) {
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
                if (rawRank <= 100) { result.totalAt100++; if (isConsistent) result.consistentAt100++; }
            }
            rawRank++;

            // --- 2. FILTERING ---
            if (!predictedEntity.equals(correctEntity) && allKnownFacts.contains(factString)) {
                continue;
            }

            if (recordCandidates) {
                result.filteredCandidates.add(candidate);
            }

            // --- 3. TIE DETECTION ---
            if (currentTieScore == null || !candidate.confidences.equals(currentTieScore)) {
                if (targetInBlock) break;

                currentTieScore = candidate.confidences;
                tieBlockStartRank = currentFilteredRank;
                entitiesInTieBlock = 0;
            }

            entitiesInTieBlock++;
            currentFilteredRank++;

            if (predictedEntity.equals(correctEntity)) {
                targetInBlock = true;
            }

            if (currentFilteredRank > maxRanks && !targetInBlock && !candidate.confidences.equals(currentTieScore)) {
                break;
            }
        }

        // --- 4. CALCULATE EXPECTED METRICS ---
        if (targetInBlock) {
            int tieBlockEndRank = tieBlockStartRank + entitiesInTieBlock - 1;

            // Expected MRR: average of (1 / rank) for the tie block
            double mrrSum = 0.0;
            for (int r = tieBlockStartRank; r <= tieBlockEndRank; r++) {
                mrrSum += 1.0 / r;
            }
            result.mrrContribution = mrrSum / entitiesInTieBlock;

            // Expected Hits@K
            result.expectedHitsAt1 = calculateExpectedHits(tieBlockStartRank, tieBlockEndRank, 1);
            result.expectedHitsAt5 = calculateExpectedHits(tieBlockStartRank, tieBlockEndRank, 5);
            result.expectedHitsAt10 = calculateExpectedHits(tieBlockStartRank, tieBlockEndRank, 10);
        }

        return result;
    }

    public Metrics evaluate(String testPath, int limitN) {
        return evaluate(testPath, null, limitN);
    }

    public Metrics evaluate(String testPath, String logPath, int limitN) {

        DoubleAdder hits1 = new DoubleAdder();
        DoubleAdder hits5 = new DoubleAdder();
        DoubleAdder hits10 = new DoubleAdder();
        DoubleAdder mrr = new DoubleAdder();

        DoubleAdder sem1 = new DoubleAdder();
        DoubleAdder sem5 = new DoubleAdder();
        DoubleAdder sem10 = new DoubleAdder();
        DoubleAdder sem100 = new DoubleAdder();
        AtomicInteger semQueriesAt1 = new AtomicInteger(0);
        AtomicInteger semQueriesAt5 = new AtomicInteger(0);
        AtomicInteger semQueriesAt10 = new AtomicInteger(0);
        AtomicInteger semQueriesAt100 = new AtomicInteger(0);
        AtomicInteger totalPredictions = new AtomicInteger(0);

        AtomicInteger processedLines = new AtomicInteger(0);
        int K_RANKS = 100;

        boolean doLogging = logPath != null;
        List<String> logLines = doLogging ? Collections.synchronizedList(new ArrayList<>()) : null;

        try (Stream<String> lines = Files.lines(Paths.get(testPath))) {
            Stream<String> stream = lines;
            if (limitN > 0) stream = stream.limit(limitN);
            
            stream
                    .parallel()
                    .forEach(line -> {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) return;

                String subject = parts[0];
                String predicate = parts[1];
                String object = parts[2];

                List<Rule> candidateRules = new ArrayList<>(registry.getPredictingRules(predicate));
                candidateRules.sort((r1, r2) -> Float.compare(r2.getConfidence(), r1.getConfidence()));

                // --- Object prediction ---
                Map<String, List<Float>> objectPredictions = new HashMap<>();
                String subPredKey = subject + "\t" + predicate;
                int objectStopThreshold = K_RANKS + knownObjectCounts.getOrDefault(subPredKey, 0);

                for (Rule r : candidateRules) {
                    r.apply(engine, true, subject, predicate, objectPredictions);
                    // if (objectPredictions.size() >= objectStopThreshold) break;
                }

                RankResult objectResult = calculateMetrics(objectPredictions, subject, predicate, object, true, K_RANKS, semanticManager, doLogging);

                if (objectResult.mrrContribution > 0) {
                    hits1.add(objectResult.expectedHitsAt1);
                    hits5.add(objectResult.expectedHitsAt5);
                    hits10.add(objectResult.expectedHitsAt10);
                    mrr.add(objectResult.mrrContribution);
                }

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
                if (objectResult.totalAt100 > 0) {
                    sem100.add((double) objectResult.consistentAt100 / objectResult.totalAt100);
                    semQueriesAt100.incrementAndGet();
                }
                totalPredictions.incrementAndGet();

                // --- Subject prediction ---
                Map<String, List<Float>> subjectPredictions = new HashMap<>();
                String predObjKey = predicate + "\t" + object;
                int subjectStopThreshold = K_RANKS + knownSubjectCounts.getOrDefault(predObjKey, 0);

                for (Rule r : candidateRules) {
                    r.apply(engine, false, object, predicate, subjectPredictions);
                    if (subjectPredictions.size() >= subjectStopThreshold) break;
                }

                RankResult subjectResult = calculateMetrics(subjectPredictions, object, predicate, subject, false, K_RANKS, semanticManager, doLogging);

                if (subjectResult.mrrContribution > 0) {
                    hits1.add(subjectResult.expectedHitsAt1);
                    hits5.add(subjectResult.expectedHitsAt5);
                    hits10.add(subjectResult.expectedHitsAt10);
                    mrr.add(subjectResult.mrrContribution);
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
                if (subjectResult.totalAt100 > 0) {
                    sem100.add((double) subjectResult.consistentAt100 / subjectResult.totalAt100);
                    semQueriesAt100.incrementAndGet();
                }
                totalPredictions.incrementAndGet();

                if (doLogging) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(subject).append(" ").append(predicate).append(" ").append(object).append("\n");
                    sb.append("Heads: ");
                    for (RankingTree.Candidate c : subjectResult.filteredCandidates) {
                        sb.append(c.entity).append("\t").append(c.confidences.get(0)).append("\t");
                    }
                    sb.append("\n");
                    sb.append("Tails: ");
                    for (RankingTree.Candidate c : objectResult.filteredCandidates) {
                        sb.append(c.entity).append("\t").append(c.confidences.get(0)).append("\t");
                    }
                    sb.append("\n\n");
                    logLines.add(sb.toString());
                }

                int currentProgress = processedLines.incrementAndGet();
                if (currentProgress % 1000 == 0) {
                    DualLogger.getOriginalOut().println("Processed " + currentProgress + " test facts...");
                }
            });

            if (doLogging) {
                Files.write(Paths.get(logPath), logLines);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        int total = totalPredictions.get();
        if (total > 0) {
            double h1 = hits1.sum() / total;
            double h5 = hits5.sum() / total;
            double h10 = hits10.sum() / total;
            double finalMrr = mrr.sum() / total;

            double s1 = semQueriesAt1.get() > 0 ? sem1.sum() / semQueriesAt1.get() : 0.0;
            double s5 = semQueriesAt5.get() > 0 ? sem5.sum() / semQueriesAt5.get() : 0.0;
            double s10 = semQueriesAt10.get() > 0 ? sem10.sum() / semQueriesAt10.get() : 0.0;
            double s100 = semQueriesAt100.get() > 0 ? sem100.sum() / semQueriesAt100.get() : 0.0;

            System.out.printf("  Evaluated %d total predictions across %d facts.\n", total, processedLines.get());

            return new Metrics(h1, h5, h10, finalMrr, s1, s5, s10, s100, total);
        }

        return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}