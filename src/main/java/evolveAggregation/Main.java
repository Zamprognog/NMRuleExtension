package evolveAggregation;

import evolveAggregation.domain.Direction;
import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.rules.RankingTree;
import evolveAggregation.rules.Rule;
import evolveAggregation.groundingEngine.GraphManager;
import evolveAggregation.groundingEngine.RuleRegistry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {

        int N = 100;
        String graphPath = "data/NELL995/data/NELL995_full_graph.nt";
//        String testRulesPath = "data/NELL995/test/all_rules.txt";
        String testRulesPath = "data/NELL995/test/test_rules.txt";

        GraphManager gm = new GraphManager();
        gm.parseTriples(graphPath, " ");
        gm.finalizeGraph();

        GroundingEngine engine = new GroundingEngine(gm.getGraph(), gm.getEntityDict(), gm.getRelationDict());

        RuleRegistry registry = new RuleRegistry();
        registry.loadRulesFromFile(testRulesPath);

//        String testSetPath = "data/NELL995/data/test_set.tsv";
        String testSetPath = "data/NELL995/test/test_set.tsv";
        String outputPath = "data/NELL995/predictions/NELL995_test_predictions.txt";
        try (
                BufferedReader br = Files.newBufferedReader(Paths.get(testSetPath));
                BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputPath))
        ){
            String line;
            int lineCount = 0;
            while ((line = br.readLine()) != null) {
                lineCount++;
                if (lineCount % 100 == 0) System.out.println("Processed " + lineCount + " lines.");
                // TSV columns are separated by a tab character (\t)
                String[] columns = line.split("\t");

                String querySubject = "<" + columns[0] +">";
                String queryPredicate = "<" + columns[1] +">";
                String queryObject = "<" + columns[2] +">";

                List<Rule> candidateRules = registry.getPredictingRules(queryPredicate);

                Map<String, TreeSet<Float>> predictions = new HashMap<>();


                for (Rule r : candidateRules) {
//
                    if (predictions.size() > N) break;
                    r.apply(engine, true, querySubject,queryPredicate, predictions);

                }
//
                RankingTree rt = new RankingTree();
                List<RankingTree.Candidate> finalRanking = rt.getFinalRanking(predictions);
//
//
                writer.write(line);
                writer.newLine();
                printResults(writer,finalRanking);
                //System.out.println(predictions);
//
            }
        } catch (IOException e) {
            System.err.println("Error reading or opening the file: " + e.getMessage());
        }

    }

    private static void printResults(BufferedWriter writer, List<RankingTree.Candidate> predictions) throws IOException {
        for (RankingTree.Candidate candidate : predictions) {
            writer.write(candidate.toBestConfString());
        }
        writer.newLine();
    }
}
