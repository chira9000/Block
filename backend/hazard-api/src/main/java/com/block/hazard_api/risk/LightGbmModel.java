package com.block.hazard_api.risk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A binary LightGBM model read from its text file (Booster.save_model), evaluated in pure Java.
 *
 * Each tree is a list of split nodes (feature, threshold, left/right child) and leaf values.
 * A child index below 0 points to a leaf: leaf number = -child - 1.
 * Raw score = sum of the leaf reached in every tree; probability = sigmoid(raw score).
 * Only numerical splits are supported (the shipped models have no categorical features).
 */
public final class LightGbmModel {

    private static final double ZERO_THRESHOLD = 1e-35; // LightGBM's kZeroThreshold

    private record Tree(int[] splitFeature, double[] threshold, int[] decisionType,
                        int[] leftChild, int[] rightChild, double[] leafValue) {}

    private final List<Tree> trees;
    private final double sigmoid;
    private final int numFeatures;

    private LightGbmModel(List<Tree> trees, double sigmoid, int numFeatures) {
        this.trees = trees;
        this.sigmoid = sigmoid;
        this.numFeatures = numFeatures;
    }

    public int numTrees() {
        return trees.size();
    }

    /** Sum of the leaf values over all trees (log-odds). */
    public double predictRaw(double[] x) {
        if (x.length != numFeatures) {
            throw new IllegalArgumentException("expected " + numFeatures + " features, got " + x.length);
        }
        double sum = 0.0;
        for (Tree t : trees) {
            sum += leafValue(t, x);
        }
        return sum;
    }

    /** Probability of the positive class, as LightGBM's predict() returns it. */
    public double predictProbability(double[] x) {
        return 1.0 / (1.0 + Math.exp(-sigmoid * predictRaw(x)));
    }

    private static double leafValue(Tree t, double[] x) {
        if (t.splitFeature().length == 0) return t.leafValue()[0]; // single-leaf tree
        int node = 0;
        while (node >= 0) {
            node = goLeft(t, node, x[t.splitFeature()[node]]) ? t.leftChild()[node] : t.rightChild()[node];
        }
        return t.leafValue()[-node - 1];
    }

    /** LightGBM's NumericalDecision: decision_type bit 1 = default left, bits 2-3 = missing type. */
    private static boolean goLeft(Tree t, int node, double value) {
        int type = t.decisionType()[node];
        int missingType = (type >> 2) & 3; // 0 none, 1 zero, 2 NaN
        boolean defaultLeft = (type & 2) != 0;
        if (Double.isNaN(value) && missingType != 2) value = 0.0;
        if ((missingType == 1 && Math.abs(value) <= ZERO_THRESHOLD) || (missingType == 2 && Double.isNaN(value))) {
            return defaultLeft;
        }
        return value <= t.threshold()[node];
    }

    // ------------------------------------------------------------------ parsing

    public static LightGbmModel parse(Reader text) throws IOException {
        BufferedReader in = new BufferedReader(text);
        List<Tree> trees = new ArrayList<>();
        Map<String, String> header = new HashMap<>();
        Map<String, String> tree = null;

        String line;
        while ((line = in.readLine()) != null) {
            line = line.strip();
            if (line.startsWith("end of trees")) break;
            if (line.startsWith("Tree=")) {
                if (tree != null) trees.add(toTree(tree));
                tree = new HashMap<>();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            (tree == null ? header : tree).put(line.substring(0, eq), line.substring(eq + 1));
        }
        if (tree != null) trees.add(toTree(tree));
        if (trees.isEmpty()) throw new IOException("no trees found in LightGBM model text");

        String objective = header.getOrDefault("objective", "");
        if (!objective.startsWith("binary")) throw new IOException("only binary models are supported: " + objective);
        double sigmoid = 1.0;
        for (String part : objective.split(" ")) {
            if (part.startsWith("sigmoid:")) sigmoid = Double.parseDouble(part.substring("sigmoid:".length()));
        }
        int numFeatures = Integer.parseInt(header.get("max_feature_idx")) + 1;
        return new LightGbmModel(trees, sigmoid, numFeatures);
    }

    private static Tree toTree(Map<String, String> fields) throws IOException {
        int numLeaves = Integer.parseInt(fields.get("num_leaves"));
        if (!"0".equals(fields.getOrDefault("num_cat", "0"))) throw new IOException("categorical splits not supported");
        if (!"0".equals(fields.getOrDefault("is_linear", "0"))) throw new IOException("linear trees not supported");
        double[] leaves = doubles(fields.get("leaf_value"));
        if (numLeaves == 1) {
            return new Tree(new int[0], new double[0], new int[0], new int[0], new int[0], leaves);
        }
        int[] decisionType = ints(fields.get("decision_type"));
        for (int d : decisionType) {
            if ((d & 1) != 0) throw new IOException("categorical splits not supported");
        }
        return new Tree(ints(fields.get("split_feature")), doubles(fields.get("threshold")), decisionType,
                ints(fields.get("left_child")), ints(fields.get("right_child")), leaves);
    }

    private static int[] ints(String s) {
        String[] parts = s.trim().split(" ");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    private static double[] doubles(String s) {
        String[] parts = s.trim().split(" ");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i]);
        return out;
    }
}
