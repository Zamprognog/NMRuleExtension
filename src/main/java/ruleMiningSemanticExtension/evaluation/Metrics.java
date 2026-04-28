package ruleMiningSemanticExtension.evaluation;

public class Metrics {
    private final double hits1;
    private final double hits5;
    private final double hits10;
    private final double mrr;
    private final double sem1;
    private final double sem5;
    private final double sem10;
    private final double sem100;
    private final int totalPredictions;

    public Metrics(double hits1, double hits5, double hits10, double mrr, double sem1, double sem5, double sem10, double sem100, int totalPredictions) {
        this.hits1 = hits1;
        this.hits5 = hits5;
        this.hits10 = hits10;
        this.mrr = mrr;
        this.sem1 = sem1;
        this.sem5 = sem5;
        this.sem10 = sem10;
        this.sem100 = sem100;
        this.totalPredictions = totalPredictions;
    }

    public double getHits1() { return hits1; }
    public double getHits5() { return hits5; }
    public double getHits10() { return hits10; }
    public double getMrr() { return mrr; }
    public double getSem1() { return sem1; }
    public double getSem5() { return sem5; }
    public double getSem10() { return sem10; }
    public double getSem100() { return sem100; }
    public int getTotalPredictions() { return totalPredictions; }

    /**
     * Prints a formatted single-line comparison for these metrics.
     */
    public void printRow(String modelName) {
        System.out.printf("%-15s | %-10.4f | %-10.4f | %-10.4f | %-10.4f | %-10.4f | %-10.4f%n",
                modelName, hits1, hits5, hits10, mrr, sem10, sem100);
    }
}