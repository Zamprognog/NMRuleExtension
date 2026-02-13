package evolveAggregation;

import com.fasterxml.jackson.core.format.InputAccessor;
import evolveAggregation.domain.Direction;
import evolveAggregation.domain.KG.KGEdge;
import evolveAggregation.domain.KG.KGVertex;
import evolveAggregation.domain.rules.GroundedRulePath;
import evolveAggregation.domain.rules.Rule;
import evolveAggregation.groundingEngine.GraphManager;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.groundingEngine.VariablePatternMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        String gotPath = "data/got/simpleGoT.csv";
        //String rulesPath = "data/got/Got-rules-10";
        String rulesPath = "data/got/Got-rules-easy";

        GraphManager gm = new GraphManager();
        gm.parseTriples(gotPath, " ");

        RuleRegistry registry = new RuleRegistry();
        registry.loadRulesFromFile(rulesPath);

//        VariablePatternMatcher pm=  new VariablePatternMatcher();


        String querySubject = "Cersei_Lannister";
        String queryPredicate = "ALLIED_WITH";
        String queryObject = "House_Baratheon_of_King's_Landing";
        List<Rule> candidateRules = registry.getPredictingRules(queryPredicate);
        //Map<String, List<List<KGEdge>>> predictions = new HashMap<>();
        Map<String, List<GroundedRulePath>> predictions = new HashMap<>();
        for (Rule r : candidateRules) {
//            pm.applyRule(gm,r,querySubject, r.getHead().getObject(), Direction.FORWARD,predictions);
            r.apply(gm, true, querySubject, Direction.FORWARD, predictions);
//            for (KGVertex v : gm.getGraph().vertexSet()) {
//                pm.applyRule(gm,v.getUri(),r, predictions,Direction.FORWARD);
//            }
        }
        System.out.println(predictions);
    }
}
