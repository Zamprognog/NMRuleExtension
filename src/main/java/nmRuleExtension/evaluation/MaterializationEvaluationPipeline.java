package nmRuleExtension.evaluation;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;
import nmRuleExtension.utils.DualLogger;
import nmRuleExtension.utils.ExperimentConfig;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MaterializationEvaluationPipeline {

    /** The named graph the materialized triples are loaded into, as in the GraphDB workflow. */
    private static final String NEW_TRIPLES_GRAPH = "http://newtriples/";

    private static void loadTriples(Model model, String path) {
        if (path == null) return;
        File file = new File(path);
        if (!file.exists()) {
            System.err.println("Warning: File not found: " + path);
            return;
        }

        try {
            if (path.endsWith(".nt") || path.endsWith(".nq")) {
                model.read(new java.io.FileInputStream(path), null, "N-TRIPLE");
            } else if (path.endsWith(".ttl")) {
                model.read(new java.io.FileInputStream(path), null, "TURTLE");
            } else if (path.endsWith(".owl") || path.endsWith(".rdf")) {
                model.read(new java.io.FileInputStream(path), null, "RDF/XML");
            } else {
                // Assuming TSV for train/valid/test based on RunMaterialization's DataLoader
                try (java.util.stream.Stream<String> lines = java.nio.file.Files.lines(java.nio.file.Paths.get(path))) {
                    lines.forEach(line -> {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 3) {
                            model.add(
                                    model.createResource(parts[0]),
                                    model.createProperty(parts[1]),
                                    model.createResource(parts[2])
                            );
                        }
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading " + path + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
//        String configPath = args.length > 0 ? args[0] : "data/NELL995/NELL995.json";
        String configPath = args.length > 0 ? args[0] : "data/YAGO4.5/yago4.5.json";
//        String configPath = args.length > 0 ? args[0] : "data/CSKG2/CSKG2.json";
        String specificFilePath = args.length > 1 ? args[1] : null;

        String namedGraphURI = NEW_TRIPLES_GRAPH;

        try {
            // 1. Load Configurationz
            ExperimentConfig config = ExperimentConfig.load(configPath);
            String predictionsDir = config.predictionsDir;
            String datasetName = config.datasetName;

            // Setup Logger
            DualLogger.setupLogger(predictionsDir, datasetName, "", "_log_materialization_");

            System.out.println("==================================================");
            System.out.println("Evaluating Materialization for Dataset: " + datasetName);
            System.out.println("==================================================");

            System.out.println("1. Loading base graph and applying RDFS reasoning...");

            // 1. Load the base graph
            Model baseModel = ModelFactory.createDefaultModel();
            // Load the full graph and ontology
            if (config.graph != null && new File(config.graph).exists()) {
                System.out.println("Loading full graph from: " + config.graph);
                loadTriples(baseModel, config.graph);
                System.out.println("  - Base graph triples loaded: " + baseModel.size());
            } else {
                System.err.println("Warning: Full graph not found or not specified in config.");
            }

            if (config.schema != null && new File(config.schema).exists()) {
                System.out.println("Loading schema (TBox) from: " + config.schema);
                long before = baseModel.size();
                loadTriples(baseModel, config.schema);
                System.out.println("  - Schema triples loaded: " + (baseModel.size() - before) + " (Total: " + baseModel.size() + ")");
            }
            if (config.typesFile != null && new File(config.typesFile).exists()) {
                System.out.println("Loading entity types from: " + config.typesFile);
                long before = baseModel.size();
                loadTriples(baseModel, config.typesFile);
                System.out.println("  - Entity types triples loaded: " + (baseModel.size() - before) + " (Total: " + baseModel.size() + ")");
            }

            System.out.println("Summary of loaded base model:");
            System.out.println("  - Total Triples: " + baseModel.size());
            System.out.println("  - Distinct Subjects: " + baseModel.listSubjects().toList().size());
            System.out.println("  - Distinct Predicates: " + baseModel.listStatements().mapWith(s -> s.getPredicate()).toSet().size());

            // 2. Apply RDFS Reasoning
            InfModel rdfsModel = ModelFactory.createInfModel(ReasonerRegistry.getRDFSSimpleReasoner(), baseModel);

            // 3. Create a Dataset and set the reasoned model as the default graph
            Dataset dataset = DatasetFactory.create();
            dataset.setDefaultModel(rdfsModel);

            System.out.println("Base graph loaded. Starting evaluation...");

            if (specificFilePath != null) {
                File specificPath = new File(specificFilePath);
                if (specificPath.isFile()) {
                    evaluateSingleFile(dataset, specificPath, namedGraphURI, datasetName);
                } else if (specificPath.isDirectory()) {
                    // A materialization run directory: evaluate every .nt file it contains.
                    File[] files = specificPath.listFiles((d, name) -> name.endsWith(".nt"));
                    if (files != null && files.length > 0) {
                        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                        System.out.println("Evaluating " + files.length + " materialized files in " + specificPath.getAbsolutePath());
                        for (File file : files) {
                            evaluateSingleFile(dataset, file, namedGraphURI, datasetName);
                        }
                    } else {
                        System.out.println("No materialized files found in " + specificPath.getAbsolutePath());
                    }
                } else {
                    System.err.println("Error: Path not found: " + specificFilePath);
                }
            } else {
                // 4. Iterate through your materialized triple files
                File baseDir = new File(predictionsDir + "/materialization/");
                File[] subDirs = baseDir.listFiles(File::isDirectory);
                File dir = baseDir;

                if (subDirs != null && subDirs.length > 0) {
                    // Sort subdirectories by name (lexicographic order) to find the latest timestamp
                    java.util.Arrays.sort(subDirs, (a, b) -> b.getName().compareTo(a.getName()));
                    dir = subDirs[0];
                    System.out.println("Selecting latest materialization folder: " + dir.getAbsolutePath());
                } else {
                    System.out.println("No subdirectories found in " + baseDir.getAbsolutePath() + ". Looking in base directory.");
                }

                // The issue specifies: predictions/[dataset]_[ruleset]_[standard/semantic]_[10/30]_[triples/rules]_[timestamp].nt
                File[] files = dir.listFiles((d, name) -> name.endsWith(".nt") && name.contains(datasetName));
//            File[] files = dir.listFiles((d, name) -> name.equals("NELL995_anyburl_semantic_10_20260401_164741_new_triples.nt"));

                if (files != null && files.length > 0) {
                    for (File file : files) {
                        evaluateSingleFile(dataset, file, namedGraphURI, datasetName);
                    }
                } else {
                    System.out.println("No materialized files found in " + dir.getAbsolutePath());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void evaluateSingleFile(Dataset dataset, File file, String namedGraphURI, String datasetName) {
        System.out.println("\n--------------------------------------------------");
        System.out.println("Evaluating: " + file.getName());

        // 5. Load the new triples into a temporary model
        Model newTriplesModel = ModelFactory.createDefaultModel();
        newTriplesModel.read(file.getAbsolutePath(), "N-TRIPLES");
//                    System.out.println("  - Triples in " + file.getName() + ": " + newTriplesModel.size());

        // 6. Add it to the dataset as a named graph
        dataset.addNamedModel(namedGraphURI, newTriplesModel);

        // 7. Run Queries
        runEvaluationQueries(dataset, datasetName);

        // 8. Clear the named graph for the next iteration
        dataset.removeNamedModel(namedGraphURI);
    }



    /**
     * Per-file metrics, mirroring the GraphDB workflow's {@code queries/metrics_default.rq} and
     * {@code queries/metrics_owl2bench.rq} (~/graphdb-import/graphdb-workflow), which is the
     * authoritative evaluation. One query returns every count in a single row:
     * {@code func}, {@code dom}, {@code ran} (plus {@code invf} for OWL2Bench), and {@code inc} —
     * the DISTINCT union of the checks, so a triple tripping several is counted once. That keeps
     * {@code 0 <= inc <= triples} and {@code sem = 1 - inc/triples} in [0,1].
     *
     * Every block starts from the new triples, so the work is bounded by |newtriples|. The
     * functional / inverse-functional checks use two OR-ed FILTER EXISTS (a conflicting value in
     * the base graph OR in newtriples) so they short-circuit on the first conflict instead of
     * enumerating all values of a high-fan-out subject.
     */
    private static void runEvaluationQueries(Dataset dataset, String datasetName) {
        boolean owl2bench = datasetName != null && datasetName.toLowerCase().contains("owl2bench");

        String qTriples = withGraph("""
                SELECT (COUNT(*) AS ?tripleCount) WHERE {
                  GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                }""");
        int triples = executeCountQuery(dataset, qTriples, "tripleCount");
        System.out.println("  - Total Materialized Triples (Named Graph): " + triples);

        Map<String, Integer> m = executeMetricsQuery(dataset, metricsQuery(owl2bench),
                owl2bench ? List.of("func", "invf", "dom", "ran", "inc")
                          : List.of("func", "dom", "ran", "inc"));

        System.out.println("  - Functional Property Violations: " + m.get("func"));
        if (owl2bench) {
            System.out.println("  - Inverse-Functional Property Violations: " + m.get("invf"));
        }
        System.out.println("  - Domain Disjointness Violations: " + m.get("dom"));
        System.out.println("  - Range Disjointness Violations: " + m.get("ran"));

        int inc = m.get("inc");
        System.out.println("  - Total Inconsistent Triples (distinct): " + inc);
        if (triples > 0) {
            System.out.printf("  - Semantic Consistency (sem): %.4f%n", 1.0 - ((double) inc / triples));
        } else {
            System.out.println("  - Semantic Consistency (sem): NA (no triples)");
        }
    }

    /** Substitutes the named-graph placeholder the same way the workflow's runner does. */
    private static String withGraph(String query) {
        return query.replace("{{NEWTRIPLES}}", "<" + NEW_TRIPLES_GRAPH + ">");
    }

    private static String metricsQuery(boolean includeInverseFunctional) {
        String invfBlock = !includeInverseFunctional ? "" : """

                  # ---- inverse-functional: (p,o) already reached from a DIFFERENT subject ----
                  { SELECT (COUNT(*) AS ?invf) WHERE {
                      SELECT DISTINCT ?s ?p ?o WHERE {
                        GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                        ?p rdf:type owl:InverseFunctionalProperty .
                        FILTER( EXISTS { ?sx ?p ?o . FILTER(?sx != ?s) }
                             || EXISTS { GRAPH {{NEWTRIPLES}} { ?sy ?p ?o . FILTER(?sy != ?s) } } )
                      }
                  } }
                """;

        String invfUnion = !includeInverseFunctional ? "" : """

                        UNION
                        { GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                          ?p rdf:type owl:InverseFunctionalProperty .
                          FILTER( EXISTS { ?sx ?p ?o . FILTER(?sx != ?s) }
                               || EXISTS { GRAPH {{NEWTRIPLES}} { ?sy ?p ?o . FILTER(?sy != ?s) } } ) }
                """;

        String projection = includeInverseFunctional ? "?func ?invf ?dom ?ran ?inc" : "?func ?dom ?ran ?inc";

        String query = """
                PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl:  <http://www.w3.org/2002/07/owl#>
                SELECT %s WHERE {

                  # ---- functional: (s,p) already has a DIFFERENT value (base or new) ----
                  { SELECT (COUNT(*) AS ?func) WHERE {
                      SELECT DISTINCT ?s ?p ?o WHERE {
                        GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                        ?p rdf:type owl:FunctionalProperty .
                        FILTER( EXISTS { ?s ?p ?ox . FILTER(?ox != ?o) }
                             || EXISTS { GRAPH {{NEWTRIPLES}} { ?s ?p ?oy . FILTER(?oy != ?o) } } )
                      }
                  } }
                %s
                  # ---- domain: subject typed with a class disjoint from the property domain ----
                  { SELECT (COUNT(*) AS ?dom) WHERE {
                      SELECT DISTINCT ?s ?p ?o WHERE {
                        GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                        ?p rdfs:domain ?domClass .
                        ?domClass owl:disjointWith|^owl:disjointWith ?type1 .
                        { ?s rdf:type ?type1 . } UNION { GRAPH {{NEWTRIPLES}} { ?s rdf:type ?type1 . } }
                      }
                  } }

                  # ---- range: object typed with a class disjoint from the property range ----
                  { SELECT (COUNT(*) AS ?ran) WHERE {
                      SELECT DISTINCT ?s ?p ?o WHERE {
                        GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                        ?p rdfs:range ?ranClass .
                        ?ranClass owl:disjointWith|^owl:disjointWith ?type2 .
                        { ?o rdf:type ?type2 . } UNION { GRAPH {{NEWTRIPLES}} { ?o rdf:type ?type2 . } }
                      }
                  } }

                  # ---- combined total, DISTINCT across the checks ----
                  { SELECT (COUNT(*) AS ?inc) WHERE {
                      SELECT DISTINCT ?s ?p ?o WHERE {
                        { GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                          ?p rdf:type owl:FunctionalProperty .
                          FILTER( EXISTS { ?s ?p ?ox . FILTER(?ox != ?o) }
                               || EXISTS { GRAPH {{NEWTRIPLES}} { ?s ?p ?oy . FILTER(?oy != ?o) } } ) }
                %s
                        UNION
                        { GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                          ?p rdfs:domain ?dc .
                          ?dc owl:disjointWith|^owl:disjointWith ?t1 .
                          { ?s rdf:type ?t1 . } UNION { GRAPH {{NEWTRIPLES}} { ?s rdf:type ?t1 . } } }
                        UNION
                        { GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                          ?p rdfs:range ?rc .
                          ?rc owl:disjointWith|^owl:disjointWith ?t2 .
                          { ?o rdf:type ?t2 . } UNION { GRAPH {{NEWTRIPLES}} { ?o rdf:type ?t2 . } } }
                      }
                  } }
                }""".formatted(projection, invfBlock, invfUnion);

        return withGraph(query);
    }

    /** Runs a one-row metrics query and pulls out the requested count variables. */
    private static Map<String, Integer> executeMetricsQuery(Dataset dataset, String queryString, List<String> vars) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String var : vars) counts.put(var, 0);

        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            if (results.hasNext()) {
                QuerySolution soln = results.nextSolution();
                for (String var : vars) {
                    if (soln.contains(var)) counts.put(var, soln.getLiteral(var).getInt());
                }
            }
        } catch (Exception e) {
            System.err.println("Error executing metrics query: " + e.getMessage());
        }
        return counts;
    }


    private static int executeCountQuery(Dataset dataset, String queryString, String returnVar) {
        Query query = QueryFactory.create(queryString);

        // Execute the query against the dataset
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            if (results.hasNext()) {
                QuerySolution soln = results.nextSolution();
                // Extract the literal value of the count variable
                return soln.getLiteral(returnVar).getInt();
            }
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
        }
        return 0;
    }

    private static void debugDomainViolations(Dataset dataset) {
        String debugQuery = withGraph("""
                PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl:  <http://www.w3.org/2002/07/owl#>
                SELECT DISTINCT ?s ?p ?domClass ?type1 WHERE {
                  GRAPH {{NEWTRIPLES}} { ?s ?p ?o . }
                  ?p rdfs:domain ?domClass .
                  ?domClass owl:disjointWith|^owl:disjointWith ?type1 .
                  { ?s rdf:type ?type1 . } UNION { GRAPH {{NEWTRIPLES}} { ?s rdf:type ?type1 . } }
                } LIMIT 20""");

        System.out.println("\n--- DEBUG: First 20 Domain Violations ---");
        Query query = QueryFactory.create(debugQuery);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            int count = 1;

            if (!results.hasNext()) {
                System.out.println("  No violations found in debug query.");
            }

            while (results.hasNext()) {
                QuerySolution soln = results.nextSolution();
                System.out.println("Violation #" + count++);

                // Using .get() allows it to handle both Resources and Literals safely
                System.out.println("  Subject (?s)          : " + soln.get("s"));
                System.out.println("  Property (?p)         : " + soln.get("p"));
                System.out.println("  Expected Domain (?dom): " + soln.get("domClass"));
                System.out.println("  Conflicting Type      : " + soln.get("type1"));
                System.out.println("-----------------------------------------");
            }
        } catch (Exception e) {
            System.err.println("Error executing debug query: " + e.getMessage());
        }
    }
}