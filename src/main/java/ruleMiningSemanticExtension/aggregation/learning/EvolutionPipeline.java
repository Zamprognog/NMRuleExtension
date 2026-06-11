package ruleMiningSemanticExtension.aggregation.learning;

import ruleMiningSemanticExtension.aggregation.PredictionPool;
import ruleMiningSemanticExtension.evaluation.Evaluator;
import ruleMiningSemanticExtension.evaluation.Metrics;
import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;
import ruleMiningSemanticExtension.groundingEngine.RuleRegistry;
import ruleMiningSemanticExtension.rules.Rule;
import ruleMiningSemanticExtension.utils.DataLoader;
import ruleMiningSemanticExtension.utils.ExperimentConfig;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Orchestrates the entire genetic learning pipeline with memory efficiency:
 * 1. Loads graph and rules.
 * 2. Pre-computes prediction pools ONLY for the validation set.
 * 3. Evolves an aggregator using Genetic Programming.
 * 4. Evaluates the best evolved aggregator on the test set lazily (on-the-fly).
 * 5. Evaluates the RankingTree baseline once after all evolution runs.
 */
public class EvolutionPipeline {

    private final ExperimentConfig config;
    private final Set<Long>   allKnownFactIds     = new HashSet<>();
    private final Set<String> allKnownFactStrings  = new HashSet<>();
    private final Set<String> trainKnownFactStrings = new HashSet<>();
    private final List<EvaluationQuery> validationQueries = new ArrayList<>();
    private final SemanticGraphManager semanticGm = new SemanticGraphManager();
    private final RuleRegistry registry = new RuleRegistry();
    private GroundingEngine engine;
    private Evaluator evaluator;

    public EvolutionPipeline(ExperimentConfig config) {
        this.config = config;
    }

    public static void main(String[] args) throws IOException {
        String configPath = args.length > 0 ? args[0] : "data/NELL995/NELL995.json";
        ExperimentConfig config = ExperimentConfig.load(configPath);

        int    batchSize     = 3000;
        int    generations   = 200;
        int    steadyFitness = 75;
        int    treeDepth     = 0; // 0 = use aggregator default
        String featureGroup  = "all";
        String logDir        = "logs";

        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String[] kv = args[i].substring(2).split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "aggregator_type"     -> config.aggregatorType     = kv[1];
                case "num_runs"            -> config.numRuns            = Integer.parseInt(kv[1]);
                case "seed"                -> config.seed               = Long.parseLong(kv[1]);
                case "validation_fraction" -> config.validationFraction = Double.parseDouble(kv[1]);
                case "batch_size"          -> batchSize                 = Integer.parseInt(kv[1]);
                case "steady_fitness"      -> steadyFitness             = Integer.parseInt(kv[1]);
                case "generations"         -> generations               = Integer.parseInt(kv[1]);
                case "tree_depth"          -> treeDepth                 = Integer.parseInt(kv[1]);
                case "feature_group"       -> featureGroup              = kv[1];
                case "log_dir"             -> logDir                    = kv[1];
            }
        }

        EvolutionPipeline pipeline = new EvolutionPipeline(config);
        System.out.println("Starting Evolution Pipeline...");
        pipeline.run(batchSize, generations, steadyFitness, treeDepth, featureGroup, logDir);
    }

    public void run(int batchSize, int generations, int steadyFitness, int treeDepth,
                    String featureGroup, String logDir) throws IOException {
        // Feature masking must be set before any extract() calls (Phase 3).
        FeatureExtractor.setFeatureGroup(featureGroup);
        String fg = FeatureExtractor.getFeatureGroup();

        String aggregatorType     = config.aggregatorType != null ? config.aggregatorType : "hybrid";
        int    numRuns            = config.numRuns > 0 ? config.numRuns : 1;
        double validationFraction = config.validationFraction > 0 ? config.validationFraction : 0.7;
        long   validTotal         = Files.lines(Paths.get(config.valid)).count();
        int    validationSize     = (int) Math.round(validTotal * validationFraction);

        Files.createDirectories(Paths.get(logDir));
        String timestamp  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String datasetTag = config.datasetName != null ? config.datasetName : "unknown";
        String logName    = datasetTag + "_" + aggregatorType + "_" + fg + "_" + timestamp + ".log";
        PrintWriter log   = new PrintWriter(new BufferedWriter(new FileWriter(logDir + "/" + logName)));

        writeLogHeader(log, datasetTag, aggregatorType, fg, validationSize, validTotal,
                       batchSize, generations, steadyFitness, treeDepth, numRuns);

        // --- Phase 1: Load data (once) ---
        System.out.println("--- Phase 1: Loading Data ---");
        semanticGm.parseTriples(config.train, "\t");
        semanticGm.finalizeGraph();
        DataLoader.loadFactsAsIds(allKnownFactIds, semanticGm.getEntityDict(), semanticGm.getRelationDict(),
                config.train, config.valid, config.test);
        if (config.schema != null) {
            semanticGm.compileConstraints(config.schema, config.typesFile);
            semanticGm.precomputeDisjointConstraints();
        }
        engine = new GroundingEngine(semanticGm);
        registry.loadRulesFromFile(config.anyburlRules, false);
        FeatureExtractor.setSemanticGraphManager(semanticGm);
        DataLoader.loadFactsIntoSet(trainKnownFactStrings, config.train);
        DataLoader.loadFactsIntoSet(allKnownFactStrings, config.train, config.valid, config.test);
        evaluator = new Evaluator(engine, registry, allKnownFactStrings, trainKnownFactStrings, semanticGm);

        // --- Phase 2: Prepare validation set (once) ---
        System.out.printf("--- Phase 2: Preparing Validation Set (%d/%d triples = %.0f%%, both directions) ---%n",
                validationSize, validTotal, validationFraction * 100);
        prepareValidationSet(config.valid, validationSize);

        // --- Phase 3: Pre-compute features (once; masking already applied via setFeatureGroup) ---
        System.out.println("--- Phase 3: Pre-computing features for " + validationQueries.size() + " validation queries ---");
        List<AggregatorFitnessFunction.ValidationQuery> fitnessQueries = validationQueries.stream()
                .map(q -> AggregatorFitnessFunction.ValidationQuery.from(
                        q.queryInfo, q.pool, q.correctEntity, q.filteredEntities))
                .toList();

        System.out.println("--- Phase 3b: Fitting min-max normalization ---");
        FeatureExtractor.fitNormalization(fitnessQueries);

        // --- Per-run tracking ---
        List<Metrics> evolvedResults = new ArrayList<>(numRuns);
        List<Long>    runSeeds       = new ArrayList<>(numRuns);
        List<Long>    runTimesMs     = new ArrayList<>(numRuns);
        List<Integer> runFinalGens   = new ArrayList<>(numRuns);

        for (int run = 0; run < numRuns; run++) {
            long seed = config.seed + run;
            System.out.printf("%n=== Run %d/%d (seed=%d) ===%n", run + 1, numRuns, seed);
            log.printf("%n--- Run %d/%d (seed=%d) ---%n", run + 1, numRuns, seed);

            EvolvableAggregator bestAggregator = createAggregator(aggregatorType);
            if (treeDepth > 0) bestAggregator.setTreeDepth(treeDepth);
            System.out.printf("--- Running Evolution: aggregator=%s, generations=%d, batch=%d ---%n",
                    aggregatorType, generations, batchSize);

            long t0       = System.currentTimeMillis();
            int finalGen  = evolveBestAggregator(fitnessQueries, bestAggregator, batchSize,
                                                  generations, steadyFitness, seed);
            long elapsedMs = System.currentTimeMillis() - t0;

            String genome = bestAggregator.toString();
            System.out.println("Best genome:             " + genome);
            System.out.printf( "Converged at generation: %d  (%.1fs)%n", finalGen, elapsedMs / 1000.0);
            log.println("Best genome:             " + genome);
            log.printf( "Converged at generation: %d%n", finalGen);
            log.printf( "Wall-clock time:         %.1fs%n", elapsedMs / 1000.0);

            System.out.println("\n--- Evaluating Evolved Aggregator on Test Set ---");
            Metrics evolvedMetrics = evaluator.evaluate(config.test, bestAggregator, -1);
            evolvedResults.add(evolvedMetrics);
            runSeeds.add(seed);
            runTimesMs.add(elapsedMs);
            runFinalGens.add(finalGen);

            log.println("\nEvolved:");
            writeMetrics(log, evolvedMetrics);
            log.flush();
        }

        // --- Baseline — evaluated once after all evolution runs ---
        System.out.println("\n--- Evaluating Baseline (RankingTree) on Test Set ---");
        Metrics baselineMetrics = evaluator.evaluate(config.test, -1);
        log.println("\nBaseline (RankingTree):");
        writeMetrics(log, baselineMetrics);

        if (numRuns > 1) {
            logSummary(log, evolvedResults, baselineMetrics);
        }

        log.close();
        System.out.println("\nResults logged to " + logDir + "/" + logName);

        writeCsvRows(datasetTag, aggregatorType, fg, treeDepth, batchSize, steadyFitness,
                     evolvedResults, runSeeds, runTimesMs, runFinalGens, baselineMetrics, logDir);
    }

    // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------

    private void writeLogHeader(PrintWriter log, String dataset, String aggregatorType,
                                String featureGroup, int validationSize, long validTotal,
                                int batchSize, int generations, int steadyFitness,
                                int treeDepth, int numRuns) {
        log.println("=== Experiment ===");
        log.println("Dataset:             " + dataset);
        log.println("Aggregator:          " + aggregatorType);
        log.println("Feature group:       " + featureGroup);
        log.printf( "Validation size:     %d / %d (%.0f%%)%n",
                validationSize, validTotal, (double) validationSize / validTotal * 100);
        log.println("Batch size:          " + batchSize);
        log.println("Generations cap:     " + generations);
        log.println("Steady fitness:      " + steadyFitness);
        log.println("Tree depth:          " + (treeDepth > 0 ? treeDepth : "default"));
        log.println("Num runs:            " + numRuns);
        log.println("Base seed:           " + config.seed);
        log.flush();
    }

    private void writeMetrics(PrintWriter log, Metrics m) {
        log.printf("  MRR:    %.4f%n", m.getMrr());
        log.printf("  H@1:    %.4f%n", m.getHits1());
        log.printf("  H@5:    %.4f%n", m.getHits5());
        log.printf("  H@10:   %.4f%n", m.getHits10());
        log.printf("  Sem@10: %.4f%n", m.getSem10());
        log.printf("  Sem@100:%.4f%n", m.getSem100());
        log.printf("  Total:  %d%n",   m.getTotalPredictions());
    }

    private void logSummary(PrintWriter log, List<Metrics> evolved, Metrics baseline) {
        log.printf("%n=== Summary (%d runs) ===%n", evolved.size());
        log.printf("%-12s  %-22s  %-22s%n", "", "Evolved (mean ± std)", "Baseline (RankingTree)");
        logSummaryRow(log, "MRR",     evolved, baseline, Metrics::getMrr);
        logSummaryRow(log, "H@1",     evolved, baseline, Metrics::getHits1);
        logSummaryRow(log, "H@5",     evolved, baseline, Metrics::getHits5);
        logSummaryRow(log, "H@10",    evolved, baseline, Metrics::getHits10);
        logSummaryRow(log, "Sem@10",  evolved, baseline, Metrics::getSem10);
        logSummaryRow(log, "Sem@100", evolved, baseline, Metrics::getSem100);
        log.flush();
    }

    @FunctionalInterface
    private interface MetricGetter { double get(Metrics m); }

    private void logSummaryRow(PrintWriter log, String label,
                               List<Metrics> evolved, Metrics baseline,
                               MetricGetter getter) {
        double[] ev = evolved.stream().mapToDouble(getter::get).toArray();
        log.printf("%-12s  %-22s  %.4f%n", label, formatMeanStd(ev), getter.get(baseline));
    }

    private static String formatMeanStd(double[] values) {
        double mean     = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        return String.format("%.4f ± %.4f", mean, Math.sqrt(variance));
    }

    private void writeCsvRows(String dataset, String aggregator, String featureGroup,
                               int treeDepth, int batchSize, int patience,
                               List<Metrics> evolvedResults, List<Long> seeds,
                               List<Long> timesMs, List<Integer> finalGens,
                               Metrics baseline, String logDir) throws IOException {
        String  csvPath     = logDir + "/results_" + dataset + ".csv";
        boolean writeHeader = !Files.exists(Paths.get(csvPath));
        try (PrintWriter csv = new PrintWriter(new BufferedWriter(new FileWriter(csvPath, true)))) {
            if (writeHeader) {
                csv.println("dataset,aggregator,feature_group,depth,batch,patience,run,seed," +
                            "mrr,h1,h5,h10,sem10,time_s,final_gen," +
                            "mrr_baseline,h1_baseline,h5_baseline,h10_baseline,sem10_baseline");
            }
            for (int i = 0; i < evolvedResults.size(); i++) {
                Metrics m = evolvedResults.get(i);
                csv.printf("%s,%s,%s,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.1f,%d," +
                           "%.4f,%.4f,%.4f,%.4f,%.4f%n",
                        dataset, aggregator, featureGroup, treeDepth, batchSize, patience,
                        i + 1, seeds.get(i),
                        m.getMrr(), m.getHits1(), m.getHits5(), m.getHits10(), m.getSem10(),
                        timesMs.get(i) / 1000.0, finalGens.get(i),
                        baseline.getMrr(), baseline.getHits1(), baseline.getHits5(),
                        baseline.getHits10(), baseline.getSem10());
            }
        }
        System.out.println("CSV results appended to " + csvPath);
    }

    // -------------------------------------------------------------------------
    // Evolution
    // -------------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EvolvableAggregator createAggregator(String type) {
        return switch (type == null ? "hybrid" : type.toLowerCase()) {
            case "linear"   -> new LinearCombinationAggregator();
            case "symbolic" -> new SymbolicAggregator();
            case "mixed"    -> new MixedAggregator();
            default         -> new HybridAggregator();
        };
    }

    /**
     * Runs GP evolution and returns the generation at which it converged.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private int evolveBestAggregator(
            List<AggregatorFitnessFunction.ValidationQuery> fitnessQueries,
            EvolvableAggregator prototype,
            int batchSize,
            int generations,
            int steadyFitness,
            long seed) {

        AggregatorFitnessFunction fitnessFunction =
                new AggregatorFitnessFunction(fitnessQueries, prototype, batchSize);
        io.jenetics.util.Factory gtf = prototype.getGenotypeFactory();

        io.jenetics.engine.Engine evolutionEngine = io.jenetics.engine.Engine
                .builder(fitnessFunction::evaluate, gtf)
                .optimize(io.jenetics.Optimize.MAXIMUM)
                .build();

        java.util.function.Predicate steadyFitnessPredicate =
                io.jenetics.engine.Limits.bySteadyFitness(steadyFitness);
        java.util.function.Predicate genLimit =
                io.jenetics.engine.Limits.byFixedGeneration(generations);

        int[] finalGen = {0};

        io.jenetics.Genotype best = io.jenetics.util.RandomRegistry.with(
                new java.util.Random(seed),
                rng -> (io.jenetics.Genotype) evolutionEngine.stream()
                        .limit(steadyFitnessPredicate.and(genLimit))
                        .peek(result -> {
                            var res = (io.jenetics.engine.EvolutionResult) result;
                            finalGen[0] = (int) res.generation();
                            if (res.generation() % 10 == 0) {
                                System.out.printf("  Generation %d: Best MRR = %.4f%n",
                                        res.generation(), ((Number) res.bestFitness()).doubleValue());
                            }
                        })
                        .collect(io.jenetics.engine.EvolutionResult.toBestGenotype())
        );

        prototype.configure(best);
        return finalGen[0];
    }

    // -------------------------------------------------------------------------
    // Validation set preparation
    // -------------------------------------------------------------------------

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

        for (List<TripleInfo> list : triplesByPredicate.values()) Collections.shuffle(list);

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
        ConcurrentHashMap<String, Map<String, PredictionCandidate>> cache = new ConcurrentHashMap<>();
        List<EvaluationQuery> groundedQueries = selectedTriples.parallelStream()
            .flatMap(t -> Stream.of(groundTriple(t, true, cache), groundTriple(t, false, cache)))
            .filter(Objects::nonNull)
            .toList();
        System.out.println("Grounding cache: " + cache.size() + " unique (predicate, startNode, direction) entries");
        validationQueries.addAll(groundedQueries);

        System.out.println("Validation Queries Loaded: " + validationQueries.size());
    }

    private Map<String, PredictionCandidate> buildPredictions(
            List<Rule> rules, String predicate, String startNode, boolean predictObject) {
        Map<String, PredictionCandidate> map = new HashMap<>();
        for (Rule r : rules) {
            r.apply(engine, predictObject, startNode, predicate, map);
        }
        map.values().forEach(c -> c.setPredictObject(predictObject));
        return map;
    }

    private EvaluationQuery groundTriple(TripleInfo triple, boolean predictObject,
            ConcurrentHashMap<String, Map<String, PredictionCandidate>> cache) {
        List<Rule> rules = registry.getPredictingRules(triple.predicate);
        if (rules.isEmpty()) return null;

        String startNode     = predictObject ? triple.subject : triple.object;
        String correctTarget = predictObject ? triple.object  : triple.subject;

        Map<String, PredictionCandidate> predictions;
        if (cache != null) {
            String cacheKey = triple.predicate + "|" + startNode + "|" + predictObject;
            predictions = cache.computeIfAbsent(cacheKey,
                    k -> buildPredictions(rules, triple.predicate, startNode, predictObject));
        } else {
            predictions = buildPredictions(rules, triple.predicate, startNode, predictObject);
        }

        if (predictions.isEmpty()) return null;

        int predId   = semanticGm.getRelationDict().lookup(triple.predicate);
        int anchorId = semanticGm.getEntityDict().lookup(startNode);
        Set<String> filteredEntities = new HashSet<>();
        if (predId != -1 && anchorId != -1) {
            for (String entity : predictions.keySet()) {
                if (entity.equals(correctTarget)) continue;
                int candidateId = semanticGm.getEntityDict().lookup(entity);
                if (candidateId == -1) continue;
                long key = predictObject
                        ? DataLoader.encodeTriple(anchorId, predId, candidateId)
                        : DataLoader.encodeTriple(candidateId, predId, anchorId);
                if (allKnownFactIds.contains(key)) filteredEntities.add(entity);
            }
        }

        String queryDesc = predictObject
                ? triple.subject + " " + triple.predicate + " ?"
                : "? " + triple.predicate + " " + triple.object;

        return new EvaluationQuery(
            queryDesc,
            new PredictionPool(predictions),
            correctTarget,
            startNode,
            triple.predicate,
            predictObject,
            filteredEntities
        );
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private record TripleInfo(String subject, String predicate, String object) {}
    private record EvaluationQuery(String queryInfo, PredictionPool pool, String correctEntity,
                                   String subject, String predicate, boolean predictObject,
                                   Set<String> filteredEntities) {}
}
