package ruleMiningSemanticExtension.evaluation;

import ruleMiningSemanticExtension.aggregation.PredictionAggregator;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;
import ruleMiningSemanticExtension.groundingEngine.RankingTree;
import ruleMiningSemanticExtension.groundingEngine.RuleRegistry;
import ruleMiningSemanticExtension.rules.Rule;
import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;

import ruleMiningSemanticExtension.utils.DualLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.Collectors;
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

    /** Sorts candidates by aggregator score (descending) for use with {@link #calculateMetrics}. */
    private List<RankingTree.Candidate> sortByAggregator(
            Map<String, PredictionCandidate> predictions, PredictionAggregator aggregator) {
        return predictions.values().stream()
                .map(pc -> {
                    float score = (float) aggregator.aggregate(pc);
                    return new RankingTree.Candidate(pc.getEntity(), new float[]{score});
                })
                .sorted((a, b) -> Float.compare(b.confidences[0], a.confidences[0]))
                .collect(Collectors.toList());
    }

    private RankResult calculateMetrics(List<RankingTree.Candidate> sortedCandidates, String sourceEntity,
                                        String predicate, String correctEntity, boolean predictingObject,
                                        int maxRanks, SemanticGraphManager semanticManager, boolean recordCandidates) {

        RankResult result = new RankResult();
        if (sortedCandidates.isEmpty()) return result;

        int rawRank = 1;
        int currentFilteredRank = 1;
        int tieBlockStartRank = 1;
        int entitiesInTieBlock = 0;
        boolean targetInBlock = false;
        float[] currentTieScore = null;

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
            if (currentTieScore == null || !Arrays.equals(candidate.confidences, currentTieScore)) {
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

    /**
     * Evaluates a learned aggregator on the test set using the same protocol as the AnyBURL baseline:
     * filtered ranking, expected MRR over tie blocks, and semantic consistency metrics.
     * Candidates are sorted by aggregator score (descending); ties are detected on the score value.
     */
    public Metrics evaluate(String testPath, PredictionAggregator aggregator, int limitN) {
        DoubleAdder hits1 = new DoubleAdder(), hits5 = new DoubleAdder(), hits10 = new DoubleAdder();
        DoubleAdder mrr   = new DoubleAdder();
        DoubleAdder sem1  = new DoubleAdder(), sem5  = new DoubleAdder();
        DoubleAdder sem10 = new DoubleAdder(), sem100 = new DoubleAdder();
        AtomicInteger semQueriesAt1   = new AtomicInteger(0), semQueriesAt5   = new AtomicInteger(0);
        AtomicInteger semQueriesAt10  = new AtomicInteger(0), semQueriesAt100 = new AtomicInteger(0);
        AtomicInteger totalPredictions = new AtomicInteger(0);
        AtomicInteger processedLines  = new AtomicInteger(0);
        int K_RANKS = 100;

        try (Stream<String> lines = Files.lines(Paths.get(testPath))) {
            Stream<String> stream = limitN > 0 ? lines.limit(limitN) : lines;
            stream.parallel().forEach(line -> {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) return;
                String subject = parts[0], predicate = parts[1], object = parts[2];

                List<Rule> candidateRules = new ArrayList<>(registry.getPredictingRules(predicate));
                candidateRules.sort((r1, r2) -> Float.compare(r2.getConfidence(), r1.getConfidence()));

                // --- Object prediction ---
                Map<String, PredictionCandidate> objectPredictions = new HashMap<>();
                for (Rule r : candidateRules) r.apply(engine, true, subject, predicate, objectPredictions);

                RankResult objectResult = calculateMetrics(
                        sortByAggregator(objectPredictions, aggregator),
                        subject, predicate, object, true, K_RANKS, semanticManager, false);
                accumulateResult(objectResult, mrr, hits1, hits5, hits10,
                        sem1, sem5, sem10, sem100, semQueriesAt1, semQueriesAt5, semQueriesAt10, semQueriesAt100);
                totalPredictions.incrementAndGet();

                // --- Subject prediction ---
                Map<String, PredictionCandidate> subjectPredictions = new HashMap<>();
                String predObjKey = predicate + "\t" + object;
                int subjectStopThreshold = K_RANKS + knownSubjectCounts.getOrDefault(predObjKey, 0);
                for (Rule r : candidateRules) {
                    r.apply(engine, false, object, predicate, subjectPredictions);
                    if (subjectPredictions.size() >= subjectStopThreshold) break;
                }

                RankResult subjectResult = calculateMetrics(
                        sortByAggregator(subjectPredictions, aggregator),
                        object, predicate, subject, false, K_RANKS, semanticManager, false);
                accumulateResult(subjectResult, mrr, hits1, hits5, hits10,
                        sem1, sem5, sem10, sem100, semQueriesAt1, semQueriesAt5, semQueriesAt10, semQueriesAt100);
                totalPredictions.incrementAndGet();

                if (processedLines.incrementAndGet() % 1000 == 0)
                    DualLogger.getOriginalOut().println("Processed " + processedLines.get() + " test facts...");
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        return buildMetrics(mrr, hits1, hits5, hits10, sem1, sem5, sem10, sem100,
                semQueriesAt1, semQueriesAt5, semQueriesAt10, semQueriesAt100, totalPredictions);
    }

    public Metrics evaluate(String testPath, int limitN) {
        return evaluate(testPath, (String) null, limitN);
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
                Map<String, PredictionCandidate> objectPredictions = new HashMap<>();
                String subPredKey = subject + "\t" + predicate;
                int objectStopThreshold = K_RANKS + knownObjectCounts.getOrDefault(subPredKey, 0);

                for (Rule r : candidateRules) {
                    r.apply(engine, true, subject, predicate, objectPredictions);
                    // if (objectPredictions.size() >= objectStopThreshold) break;
                }

                RankResult objectResult = calculateMetrics(
                        new RankingTree().getFinalRanking(objectPredictions),
                        subject, predicate, object, true, K_RANKS, semanticManager, doLogging);

                accumulateResult(objectResult, mrr, hits1, hits5, hits10,
                        sem1, sem5, sem10, sem100, semQueriesAt1, semQueriesAt5, semQueriesAt10, semQueriesAt100);
                totalPredictions.incrementAndGet();

                // --- Subject prediction ---
                Map<String, PredictionCandidate> subjectPredictions = new HashMap<>();
                String predObjKey = predicate + "\t" + object;
                int subjectStopThreshold = K_RANKS + knownSubjectCounts.getOrDefault(predObjKey, 0);

                for (Rule r : candidateRules) {
                    r.apply(engine, false, object, predicate, subjectPredictions);
                    if (subjectPredictions.size() >= subjectStopThreshold) break;
                }

                RankResult subjectResult = calculateMetrics(
                        new RankingTree().getFinalRanking(subjectPredictions),
                        object, predicate, subject, false, K_RANKS, semanticManager, doLogging);

                accumulateResult(subjectResult, mrr, hits1, hits5, hits10,
                        sem1, sem5, sem10, sem100, semQueriesAt1, semQueriesAt5, semQueriesAt10, semQueriesAt100);
                totalPredictions.incrementAndGet();

                if (doLogging) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(subject).append(" ").append(predicate).append(" ").append(object).append("\n");
                    sb.append("Heads: ");
                    for (RankingTree.Candidate c : subjectResult.filteredCandidates) {
                        sb.append(c.entity).append("\t").append(c.confidences[0]).append("\t");
                    }
                    sb.append("\n");
                    sb.append("Tails: ");
                    for (RankingTree.Candidate c : objectResult.filteredCandidates) {
                        sb.append(c.entity).append("\t").append(c.confidences[0]).append("\t");
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

        return buildMetrics(mrr, hits1, hits5, hits10, sem1, sem5, sem10, sem100,
                semQueriesAt1, semQueriesAt5, semQueriesAt10, semQueriesAt100, totalPredictions);
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private void accumulateResult(RankResult r,
            DoubleAdder mrr, DoubleAdder hits1, DoubleAdder hits5, DoubleAdder hits10,
            DoubleAdder sem1, DoubleAdder sem5, DoubleAdder sem10, DoubleAdder sem100,
            AtomicInteger semQ1, AtomicInteger semQ5, AtomicInteger semQ10, AtomicInteger semQ100) {
        if (r.mrrContribution > 0) {
            mrr.add(r.mrrContribution);
            hits1.add(r.expectedHitsAt1);
            hits5.add(r.expectedHitsAt5);
            hits10.add(r.expectedHitsAt10);
        }
        if (r.totalAt1   > 0) { sem1.add((double)   r.consistentAt1   / r.totalAt1);   semQ1.incrementAndGet(); }
        if (r.totalAt5   > 0) { sem5.add((double)   r.consistentAt5   / r.totalAt5);   semQ5.incrementAndGet(); }
        if (r.totalAt10  > 0) { sem10.add((double)  r.consistentAt10  / r.totalAt10);  semQ10.incrementAndGet(); }
        if (r.totalAt100 > 0) { sem100.add((double) r.consistentAt100 / r.totalAt100); semQ100.incrementAndGet(); }
    }

    private Metrics buildMetrics(
            DoubleAdder mrr, DoubleAdder hits1, DoubleAdder hits5, DoubleAdder hits10,
            DoubleAdder sem1, DoubleAdder sem5, DoubleAdder sem10, DoubleAdder sem100,
            AtomicInteger semQ1, AtomicInteger semQ5, AtomicInteger semQ10, AtomicInteger semQ100,
            AtomicInteger totalPredictions) {
        int total = totalPredictions.get();
        if (total == 0) return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        return new Metrics(
            hits1.sum() / total, hits5.sum() / total, hits10.sum() / total, mrr.sum() / total,
            semQ1.get()   > 0 ? sem1.sum()   / semQ1.get()   : 0.0,
            semQ5.get()   > 0 ? sem5.sum()   / semQ5.get()   : 0.0,
            semQ10.get()  > 0 ? sem10.sum()  / semQ10.get()  : 0.0,
            semQ100.get() > 0 ? sem100.sum() / semQ100.get() : 0.0,
            total
        );
    }
}