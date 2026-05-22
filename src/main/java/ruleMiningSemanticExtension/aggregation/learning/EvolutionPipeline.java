package ruleMiningSemanticExtension.aggregation.learning;

import ruleMiningSemanticExtension.aggregation.PredictionAggregator;
import ruleMiningSemanticExtension.aggregation.PredictionPool;
import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;
import ruleMiningSemanticExtension.groundingEngine.RuleRegistry;
import ruleMiningSemanticExtension.rules.Rule;
import ruleMiningSemanticExtension.utils.DataLoader;
import ruleMiningSemanticExtension.utils.ExperimentConfig;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Orchestrates the entire genetic learning pipeline with memory efficiency:
 * 1. Loads graph and rules.
 * 2. Pre-computes prediction pools ONLY for the validation set.
 * 3. Evolves an aggregator using Genetic Programming.
 * 4. Evaluates the best evolved aggregator on the test set lazily (on-the-fly).
 */
public class EvolutionPipeline {

    private final ExperimentConfig config;
    private final Set<Long> allKnownFactIds = new HashSet<>();
    private final List<EvaluationQuery> validationQueries = new ArrayList<>();
    private final SemanticGraphManager semanticGm = new SemanticGraphManager();
    private final RuleRegistry registry = new RuleRegistry();
    private GroundingEngine engine;

    public EvolutionPipeline(ExperimentConfig config) {
        this.config = config;
    }

    public static void main(String[] args) throws IOException {
        String configPath = args.length > 0 ? args[0] : "data/NELL995/NELL995.json";
        ExperimentConfig config = ExperimentConfig.load(configPath);
        
        EvolutionPipeline pipeline = new EvolutionPipeline(config);
        
        int validationSize = 2500;
        int batchSize     = 200; // queries sampled per fitness call (re-sampled each generation)
        int generations   = 200;

        System.out.println("Starting Memory-Efficient Hybrid Pipeline Experiment...");
        pipeline.run(validationSize, batchSize, generations);
    }

    public void run(int validationSize, int batchSize, int generations) throws IOException {
        // 1. Load Data
        System.out.println("--- Phase 1: Loading Data ---");
        semanticGm.parseTriples(config.train, "\t");
        semanticGm.finalizeGraph();
        // Load after finalizeGraph so dictionaries are populated for ID encoding
        DataLoader.loadFactsAsIds(allKnownFactIds, semanticGm.getEntityDict(), semanticGm.getRelationDict(),
                config.train, config.valid, config.test);
        if (config.schema != null) {
            semanticGm.compileConstraints(config.schema, config.typesFile);
            semanticGm.precomputeDisjointConstraints();
        }
        
        engine = new GroundingEngine(semanticGm);
        registry.loadRulesFromFile(config.anyburlRules, false);
        
        FeatureExtractor.setSemanticGraphManager(semanticGm);

        // 2. Prepare Validation Set (Diverse & Parallel)
        System.out.println("--- Phase 2: Preparing Validation Set (" + validationSize + " triples, both directions) ---");
        prepareValidationSet(config.valid, validationSize);

        // 3. Run Evolution
        System.out.println("--- Phase 3: Pre-computing features for " + validationQueries.size() + " validation queries ---");
        List<AggregatorFitnessFunction.ValidationQuery> fitnessQueries = validationQueries.stream()
                .map(q -> AggregatorFitnessFunction.ValidationQuery.from(q.queryInfo, q.pool, q.correctEntity))
                .toList();

        System.out.println("--- Running Hybrid Evolution (" + generations + " generations, batch=" + batchSize + ") ---");
        HybridAggregator bestAggregator = new HybridAggregator();
        evolveBestAggregator(fitnessQueries, bestAggregator, batchSize, generations);
        
        System.out.println("\nBest Evolved Formula: " + bestAggregator);

        // 4. Final Evaluation (On-the-fly to save memory)
        System.out.println("\n--- Phase 4: Final Evaluation on Test Set ---");
        evaluateOnTestSet(config.test, bestAggregator);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void evolveBestAggregator(
            List<AggregatorFitnessFunction.ValidationQuery> fitnessQueries,
            HybridAggregator prototype,
            int batchSize,
            int generations) {

        AggregatorFitnessFunction fitnessFunction =
                new AggregatorFitnessFunction(fitnessQueries, prototype, batchSize);
        io.jenetics.util.Factory gtf = prototype.getGenotypeFactory();

        io.jenetics.engine.Engine evolutionEngine = io.jenetics.engine.Engine
                .builder(fitnessFunction::evaluate, gtf)
                .optimize(io.jenetics.Optimize.MAXIMUM)
                .build();

        // Stop when fitness hasn't improved for 20 generations OR hard generation cap is reached.
        java.util.function.Predicate steadyFitness = io.jenetics.engine.Limits.bySteadyFitness(50);
        java.util.function.Predicate genLimit = io.jenetics.engine.Limits.byFixedGeneration(generations);

        io.jenetics.Genotype best = (io.jenetics.Genotype) evolutionEngine.stream()
                .limit(steadyFitness.and(genLimit))
                .peek(result -> {
                    var res = (io.jenetics.engine.EvolutionResult) result;
                    if (res.generation() % 10 == 0) {
                        System.out.printf("Generation %d: Best MRR = %.4f\n",
                            res.generation(), ((Number) res.bestFitness()).doubleValue());
                    }
                })
                .collect(io.jenetics.engine.EvolutionResult.toBestGenotype());

        prototype.configure(best);
    }

    private void prepareValidationSet(String testPath, int size) throws IOException {
        Map<String, List<TripleInfo>> triplesByPredicate = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(testPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    triplesByPredicate.computeIfAbsent(parts[1], k -> new ArrayList<>())
                                      .add(new TripleInfo(parts[0], parts[1], parts[2]));
                }
            }
        }

        for (List<TripleInfo> list : triplesByPredicate.values()) {
            Collections.shuffle(list);
        }

        List<TripleInfo> selectedTriples = new ArrayList<>();
        List<String> predicates = new ArrayList<>(triplesByPredicate.keySet());
        int pIndex = 0;
        while (selectedTriples.size() < size && !predicates.isEmpty()) {
            String p = predicates.get(pIndex % predicates.size());
            List<TripleInfo> list = triplesByPredicate.get(p);
            if (!list.isEmpty()) {
                selectedTriples.add(list.removeFirst());
            } else {
                predicates.remove(pIndex % predicates.size());
                if (predicates.isEmpty()) break;
                pIndex--;
            }
            pIndex++;
        }

        System.out.println("Grounding validation set in parallel...");
        List<EvaluationQuery> groundedQueries = selectedTriples.parallelStream()
            .flatMap(t -> Stream.of(groundTriple(t, true), groundTriple(t, false)))
            .filter(Objects::nonNull)
            .toList();
        validationQueries.addAll(groundedQueries);
        
        System.out.println("Validation Queries Loaded: " + validationQueries.size());
    }

    private EvaluationQuery groundTriple(TripleInfo triple, boolean predictObject) {
        List<Rule> rules = registry.getPredictingRules(triple.predicate);
        if (rules.isEmpty()) return null;

        Map<String, PredictionCandidate> predictions = new HashMap<>();
        String startNode = predictObject ? triple.subject : triple.object;
        String correctTarget = predictObject ? triple.object : triple.subject;

        for (Rule r : rules) {
            r.apply(engine, predictObject, startNode, triple.predicate, predictions);
        }

        if (predictions.isEmpty()) return null;

        predictions.values().forEach(c -> c.setPredictObject(predictObject));

        String queryDesc = predictObject ? 
                triple.subject + " " + triple.predicate + " ?" : 
                "? " + triple.predicate + " " + triple.object;

        return new EvaluationQuery(
            queryDesc,
            new PredictionPool(predictions),
            correctTarget,
            startNode,
            triple.predicate,
            predictObject
        );
    }

    private void evaluateOnTestSet(String testPath, PredictionAggregator bestAggregator) throws IOException {
        PredictionAggregator baseline = candidate -> {
            List<Float> confidences = candidate.getConfidences();
            return confidences.isEmpty() ? 0.0 : Collections.max(confidences);
        };

        // Read all triples first (I/O is sequential)
        List<TripleInfo> testTriples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(testPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 3) testTriples.add(new TripleInfo(parts[0], parts[1], parts[2]));
            }
        }
        System.out.println("Evaluating " + testTriples.size() + " test triples in parallel...");

        // Ground and score in parallel; collect (evolvedRank, baselineRank) pairs
        AtomicInteger processed = new AtomicInteger(0);
        List<QueryResult> results = testTriples.parallelStream()
            .flatMap(t -> Stream.of(groundTriple(t, true), groundTriple(t, false)))
            .filter(Objects::nonNull)
            .map(q -> {
                QueryResult r = new QueryResult(filteredRank(q, bestAggregator), filteredRank(q, baseline));
                int n = processed.incrementAndGet();
                if (n % 100 == 0) System.out.println("Scored " + n + " queries...");
                return r;
            })
            .toList();

        // Aggregate sequentially — no thread-safety concerns
        MetricsTracker evolvedMetrics = new MetricsTracker();
        MetricsTracker baselineMetrics = new MetricsTracker();
        for (QueryResult r : results) {
            if (r.evolvedRank() > 0) evolvedMetrics.addRank(r.evolvedRank());
            evolvedMetrics.incrementTotal();
            if (r.baselineRank() > 0) baselineMetrics.addRank(r.baselineRank());
            baselineMetrics.incrementTotal();
        }

        System.out.println("\nResults for Best Evolved Aggregator:");
        evolvedMetrics.print();
        System.out.println("\nResults for Baseline (Max Confidence):");
        baselineMetrics.print();
    }

    /** Returns the filtered rank of the correct entity (1-based), or 0 if not found. */
    private int filteredRank(EvaluationQuery q, PredictionAggregator aggregator) {
        List<PredictionPool.ScoredCandidate> ranked = q.pool.getRankedCandidates(aggregator);
        int predId = semanticGm.getRelationDict().lookup(q.predicate);
        int anchorId = semanticGm.getEntityDict().lookup(q.subject); // q.subject stores start node for both directions
        int rank = 1;
        for (PredictionPool.ScoredCandidate candidate : ranked) {
            String entity = candidate.getEntity();
            if (entity.equals(q.correctEntity)) return rank;
            int candidateId = semanticGm.getEntityDict().lookup(entity);
            if (candidateId == -1 || predId == -1 || anchorId == -1) {
                rank++;
                continue;
            }
            long key = q.predictObject
                    ? DataLoader.encodeTriple(anchorId, predId, candidateId)
                    : DataLoader.encodeTriple(candidateId, predId, anchorId);
            if (!allKnownFactIds.contains(key)) rank++;
        }
        return 0;
    }

    private static class MetricsTracker {
        double totalMrr = 0;
        int h1 = 0, h3 = 0, h10 = 0;
        int total = 0;

        void addRank(int rank) {
            totalMrr += 1.0 / rank;
            if (rank <= 1) h1++;
            if (rank <= 3) h3++;
            if (rank <= 10) h10++;
        }

        void incrementTotal() { total++; }

        void print() {
            if (total == 0) { System.out.println("No queries evaluated."); return; }
            System.out.printf("MRR:  %.4f\n", totalMrr / total);
            System.out.printf("H@1:  %.4f\n", (double) h1 / total);
            System.out.printf("H@3:  %.4f\n", (double) h3 / total);
            System.out.printf("H@10: %.4f\n", (double) h10 / total);
            System.out.println("Total queries: " + total);
        }
    }

    private record TripleInfo(String subject, String predicate, String object) {}
    private record EvaluationQuery(String queryInfo, PredictionPool pool, String correctEntity, String subject, String predicate, boolean predictObject) {}
    private record QueryResult(int evolvedRank, int baselineRank) {}
}
