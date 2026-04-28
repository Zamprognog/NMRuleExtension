package ruleMiningSemanticExtension.evaluation;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;
import ruleMiningSemanticExtension.utils.DualLogger;
import ruleMiningSemanticExtension.utils.ExperimentConfig;

import java.io.File;

public class MaterializationEvaluationPipeline {

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

        String namedGraphURI = "http://newtriples/";

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
                File specificFile = new File(specificFilePath);
                if (specificFile.exists() && specificFile.isFile()) {
                    evaluateSingleFile(dataset, specificFile, namedGraphURI);
                } else {
                    System.err.println("Error: Specific file not found: " + specificFilePath);
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
                        evaluateSingleFile(dataset, file, namedGraphURI);
                    }
                } else {
                    System.out.println("No materialized files found in " + dir.getAbsolutePath());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void evaluateSingleFile(Dataset dataset, File file, String namedGraphURI) {
        System.out.println("\n--------------------------------------------------");
        System.out.println("Evaluating: " + file.getName());

        // 5. Load the new triples into a temporary model
        Model newTriplesModel = ModelFactory.createDefaultModel();
        newTriplesModel.read(file.getAbsolutePath(), "N-TRIPLES");
//                    System.out.println("  - Triples in " + file.getName() + ": " + newTriplesModel.size());

        // 6. Add it to the dataset as a named graph
        dataset.addNamedModel(namedGraphURI, newTriplesModel);

        // 7. Run Queries
        runEvaluationQueries(dataset);

        // 8. Clear the named graph for the next iteration
        dataset.removeNamedModel(namedGraphURI);
    }



    private static void runEvaluationQueries(Dataset dataset) {
        // Query 0: Count triples in the default graph
//        String q0 = "SELECT (COUNT(*) AS ?tripleCount) WHERE { ?s ?p ?o . }";
//        System.out.println("  - Total Base Triples (Default Graph): " + executeCountQuery(dataset, q0, "tripleCount"));

        // Query 1: Count triples in the named graph
        String q1 = "SELECT (COUNT(*) AS ?tripleCount) " +
                "WHERE { " +
                "  GRAPH <http://newtriples/> { ?s ?p ?o . } " +
                "}";

        System.out.println("  - Total Materialized Triples (Named Graph): " + executeCountQuery(dataset, q1, "tripleCount"));


        String q2 = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
                "SELECT (COUNT(*) as ?total) " +
                "WHERE { " +
                "  SELECT DISTINCT ?s ?p ?o WHERE { " +
                "    GRAPH <http://newtriples/> { ?s ?p ?o . } " +
                "    { " +
                "      SELECT ?s ?p " +
                "      WHERE { " +
                "        ?p rdf:type owl:FunctionalProperty . " +
                "        { ?s ?p ?o_inner . } " +                                  // Look in base graph
                "        UNION " +
                "        { GRAPH <http://newtriples/> { ?s ?p ?o_inner . } } " +   // Look in new predictions
                "      } " +
                "      GROUP BY ?s ?p " +
                "      HAVING (COUNT(DISTINCT ?o_inner) > 1) " +
                "    } " +
                "  } " +
                "}";

        System.out.println("  - Functional Property Violations: " + executeCountQuery(dataset, q2, "total"));

        String qDomainCheck = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> \n" +
                "SELECT (COUNT(*) AS ?domainCheckCount) \n" +
                "WHERE { \n" +
                "    SELECT DISTINCT ?s ?p ?o WHERE { \n" +
                "        # Start with new predictions\n" +
                "        GRAPH <http://newtriples/> { ?s ?p ?o . } \n" +
                "        # Look up domain constraint\n" +
                "        ?p rdfs:domain ?dom . \n" +
                "        # Check symmetric disjointness\n" +
                "        ?dom owl:disjointWith|^owl:disjointWith ?type1 . \n" +
                "        # Validate against both graphs\n" +
                "        { \n" +
                "            { ?s a ?type1 . } \n" +
                "            UNION \n" +
                "            { GRAPH <http://newtriples/> { ?s a ?type1 . } } \n" +
                "        } \n" +
                "    } \n" +
                "}";

// 2. Optimized Range Disjointness Check
        String qRangeCheck = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> \n" +
                "SELECT (COUNT(*) AS ?rangeCheckCount) \n" +
                "WHERE { \n" +
                "    SELECT DISTINCT ?s ?p ?o WHERE { \n" +
                "        # Start with new predictions\n" +
                "        GRAPH <http://newtriples/> { ?s ?p ?o . } \n" +
                "        # Look up range constraint\n" +
                "        ?p rdfs:range ?ran . \n" +
                "        # Check symmetric disjointness\n" +
                "        ?ran owl:disjointWith|^owl:disjointWith ?type2 . \n" +
                "        # Validate against both graphs\n" +
                "        { \n" +
                "            { ?o a ?type2 . } \n" +
                "            UNION \n" +
                "            { GRAPH <http://newtriples/> { ?o a ?type2 . } } \n" +
                "        } \n" +
                "    } \n" +
                "}";

//        debugDomainViolations(dataset);
        int domainViolations = executeCountQuery(dataset, qDomainCheck, "domainCheckCount");
        int rangeViolations = executeCountQuery(dataset, qRangeCheck, "rangeCheckCount");

        System.out.println("  - Domain Disjointness Violations: " + domainViolations);
        System.out.println("  - Range Disjointness Violations: " + rangeViolations);
        System.out.println("  - Total Domain/Range Violations: " + (domainViolations + rangeViolations));
//        String q4 = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" +
//                "PREFIX owl: <http://www.w3.org/2002/07/owl#>" +
//                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>" +
//                "SELECT (COUNT(*) AS ?total)" +
//                "WHERE {" +
//                "  {" +
//                "    # --- INNER QUERY: FIND THE TRIPLES ---" +
//                "    SELECT DISTINCT ?s ?p ?o" +
//                "    WHERE {" +
//                "      {" +
//                "        # 1. Functional Property" +
//                "        GRAPH <http://newtriples/> { ?s ?p ?o . }" +
//                "        {" +
//                "           SELECT ?s ?p WHERE {" +
//                "             ?p a owl:FunctionalProperty ." +
//                "             ?s ?p ?val ." +
//                "           }" +
//                "           GROUP BY ?s ?p" +
//                "           HAVING (COUNT(DISTINCT ?val) > 1)" +
//                "        }" +
//                "      }" +
//                "      UNION" +
//                "      {" +
//                "        # 2. Domain Violation" +
//                "        GRAPH <http://newtriples/> { ?s ?p ?o . }" +
//                "        ?p rdfs:domain ?dom ." +
//                "        ?s a ?type1 ." +
//                "        ?type1 owl:disjointWith ?dom ." +
//                "      }" +
//                "      UNION" +
//                "      {" +
//                "        # 3. Range Violation" +
//                "        GRAPH <http://newtriples/> { ?s ?p ?o . }" +
//                "        ?p rdfs:range ?ran ." +
//                "        ?o a ?type2 ." +
//                "        ?type2 owl:disjointWith ?ran ." +
//                "      }" +
//                "    }" +
//                "  }" +
//                "}";
//
//        System.out.println("  - All distinct Violations: " + executeCountQuery(dataset, q4, "total"));

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
        String debugQuery = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> \n" +
                "SELECT DISTINCT ?s ?p ?dom ?type1 WHERE { \n" +
                "    GRAPH <http://newtriples/> { ?s ?p ?o . } \n" +
                "    ?p rdfs:domain ?dom . \n" +
                "    ?dom owl:disjointWith|^owl:disjointWith ?type1 . \n" +
                "    { \n" +
                "        { ?s a ?type1 . } \n" +
                "        UNION \n" +
                "        { GRAPH <http://newtriples/> { ?s a ?type1 . } } \n" +
                "    } \n" +
                "} LIMIT 20";

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
                System.out.println("  Expected Domain (?dom): " + soln.get("dom"));
                System.out.println("  Conflicting Type      : " + soln.get("type1"));
                System.out.println("-----------------------------------------");
            }
        } catch (Exception e) {
            System.err.println("Error executing debug query: " + e.getMessage());
        }
    }
}