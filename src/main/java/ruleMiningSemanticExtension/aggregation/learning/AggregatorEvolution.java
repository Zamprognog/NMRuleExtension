package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.Gene;
import io.jenetics.Genotype;
import io.jenetics.Optimize;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.Limits;
import io.jenetics.util.Factory;

import java.util.List;

/**
 * Main entry point for evolving the prediction aggregator using Jenetics.
 * @param <G> The type of gene used in the genotype.
 */
public class AggregatorEvolution<G extends Gene<?, G>> {

    public void runEvolution(List<AggregatorFitnessFunction.ValidationQuery> validationSet, EvolvableAggregator<G> prototype) {
        
        AggregatorFitnessFunction<G> fitnessFunction = new AggregatorFitnessFunction<>(validationSet, prototype, 0);

        // 1. Define the genotype factory (representation of the aggregator parameters)
        Factory<Genotype<G>> gtf = prototype.getGenotypeFactory();

        // 2. Setup the evolution engine
        Engine<G, Double> engine = Engine
                .builder(fitnessFunction::evaluate, gtf)
                .optimize(Optimize.MAXIMUM)
                .build();

        // 3. Start the evolution
        Genotype<G> best = engine.stream()
                .limit(Limits.byFixedGeneration(100)) // Stop after 100 generations
                .collect(EvolutionResult.toBestGenotype());

        System.out.println("Best genotype: " + best);
        
        // Final configuration of the prototype with the best result
        prototype.configure(best);
    }
}
