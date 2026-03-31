package evolveAggregation.evaluation;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;

import java.io.File;

public class MaterializationEvaluationPipeline {

    public static void main(String[] args) {
        // Example file paths - replace with your config variables
        String fullGraphPath = "data/full_graph.nt";
        String materializedDir = "predictions/";
        String namedGraphURI = "http://newtriples/";

        System.out.println("1. Loading base graph and applying RDFS reasoning...");

        // 1. Load the base graph
        Model baseModel = ModelFactory.createDefaultModel();
        baseModel.read(fullGraphPath, "N-TRIPLES"); // Ensure format matches your file

        // 2. Apply RDFS Reasoning (equivalent to GraphDB's RDFS ruleset)
        InfModel rdfsModel = ModelFactory.createInfModel(ReasonerRegistry.getRDFSReasoner(), baseModel);

        // 3. Create a Dataset and set the reasoned model as the default graph
        Dataset dataset = DatasetFactory.create();
        dataset.setDefaultModel(rdfsModel);

        System.out.println("Base graph loaded. Starting evaluation...");

        // 4. Iterate through your materialized triple files
        File dir = new File(materializedDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".nt") && name.contains("new_triples"));

        if (files != null) {
            for (File file : files) {
                System.out.println("Evaluating: " + file.getName());

                // 5. Load the new triples into a temporary model
                Model newTriplesModel = ModelFactory.createDefaultModel();
                newTriplesModel.read(file.getAbsolutePath(), "N-TRIPLES");

                // 6. Add it to the dataset as a named graph
                dataset.addNamedModel(namedGraphURI, newTriplesModel);

                // 7. Run Queries
                runEvaluationQueries(dataset);

                // 8. Clear the named graph for the next iteration
                dataset.removeNamedModel(namedGraphURI);
            }
        }
    }

    private static void runEvaluationQueries(Dataset dataset) {
        // Query 1: Count triples in the named graph
        String q1 = "SELECT (COUNT(*) AS ?tripleCount) " +
                "WHERE { " +
                "  GRAPH <http://newtriples/> { ?s ?p ?o . } " +
                "}";

        System.out.println("  - Total Materialized Triples: " + executeCountQuery(dataset, q1, "tripleCount"));

        // Query 2: Check for Functional Property Violations
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
                "        ?s ?p ?o_inner . " +
                "      } " +
                "      GROUP BY ?s ?p " +
                "      HAVING (COUNT(DISTINCT ?o_inner) > 1) " +
                "    } " +
                "  } " +
                "}";
        System.out.println("  - Functional Property Violations: " + executeCountQuery(dataset, q2, "total"));

        String q3= "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#>" +
                "SELECT ?domainCheckCount ?rangeCheckCount" +
                "WHERE {" +
                "    {" +
                "        SELECT (COUNT(*) AS ?domainCheckCount)" +
                "        WHERE {" +
                "            SELECT DISTINCT ?s ?p ?o " +
                "            WHERE {" +
                "                GRAPH <http://newtriples/> {" +
                "                    ?s ?p ?o ." +
                "                }" +
                "                {" +
                "                    ?s a ?type1 ." +
                "                    ?p rdfs:domain ?dom ." +
                "                    ?type1 owl:disjointWith ?dom ." +
                "                }" +
                "            }" +
                "        }" +
                "    }" +
                "    {" +
                "        SELECT (COUNT(*) AS ?rangeCheckCount)" +
                "        WHERE {" +
                "            SELECT DISTINCT ?s ?p ?o " +
                "            WHERE {" +
                "                GRAPH <http://newtriples/> {" +
                "                    ?s ?p ?o ." +
                "                }" +
                "                {" +
                "                    ?o a ?type2 ." +
                "                    ?p rdfs:range ?ran ." +
                "                    ?type2 owl:disjointWith ?ran ." +
                "                }" +
                "            }" +
                "        }" +
                "    }" +
                "}";
        System.out.println("  - domain/range Constraints Violations: " + executeCountQuery(dataset, q3, "total"));

        String q4 = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#>" +
                "" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>" +
                "" +
                "SELECT (COUNT(*) AS ?totalDistinctTriples)" +
                "WHERE {" +
                "  {" +
                "    # --- INNER QUERY: FIND THE TRIPLES ---" +
                "    SELECT DISTINCT ?s ?p ?o" +
                "    WHERE {" +
                "      {" +
                "        # 1. Functional Property" +
                "        GRAPH <http://newtriples/> { ?s ?p ?o . }" +
                "        {" +
                "           SELECT ?s ?p WHERE {" +
                "             ?p a owl:FunctionalProperty ." +
                "             ?s ?p ?val ." +
                "           }" +
                "           GROUP BY ?s ?p" +
                "           HAVING (COUNT(DISTINCT ?val) > 1)" +
                "        }" +
                "      }" +
                "      UNION" +
                "      {" +
                "        # 2. Domain Violation" +
                "        GRAPH <http://newtriples/> { ?s ?p ?o . }" +
                "        ?s a ?type1 ." +
                "        ?p rdfs:domain ?dom ." +
                "        ?type1 owl:disjointWith ?dom ." +
                "      }" +
                "      UNION" +
                "      {" +
                "        # 3. Range Violation" +
                "        GRAPH <http://newtriples/> { ?s ?p ?o . }" +
                "        ?o a ?type2 ." +
                "        ?p rdfs:range ?ran ." +
                "        ?type2 owl:disjointWith ?ran ." +
                "      }" +
                "    }" +
                "  }" +
                "}";

        System.out.println("  - All distinct Violations: " + executeCountQuery(dataset, q4, "total"));

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
}