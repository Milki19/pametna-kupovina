package rs.pametnakupovina.backend.matching;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * multilingual-e5 in ONNX: the mean of the last layer over the name's
 * tokens, scaled to length 1. A name goes in as "query: " plus the name in
 * lower case, the way e5 compares two texts of the same kind.
 */
final class OnnxNameMeaning implements NameMeaning, AutoCloseable {

    // Product names are short; 64 tokens is ten long names' worth.
    private static final int MAX_TOKENS = 64;

    private final String modelCode;
    private final UnigramTokenizer tokenizer;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final boolean wantsTokenTypes;

    OnnxNameMeaning(Path model, Path tokenizerJson, String modelCode) throws IOException, OrtException {
        this.modelCode = modelCode;
        try (InputStream input = Files.newInputStream(tokenizerJson)) {
            this.tokenizer = UnigramTokenizer.read(input);
        }
        this.environment = OrtEnvironment.getEnvironment();
        this.session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
        this.wantsTokenTypes = session.getInputNames().contains("token_type_ids");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String modelCode() {
        return modelCode;
    }

    @Override
    public List<float[]> vectors(List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        long[][] ids = new long[names.size()][];
        int width = 0;
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index) == null ? "" : names.get(index).toLowerCase(Locale.ROOT).strip();
            ids[index] = tokenizer.encode("query: " + name, MAX_TOKENS);
            width = Math.max(width, ids[index].length);
        }
        long[][] padded = new long[names.size()][width];
        long[][] mask = new long[names.size()][width];
        for (int row = 0; row < names.size(); row++) {
            Arrays.fill(padded[row], tokenizer.padId());
            System.arraycopy(ids[row], 0, padded[row], 0, ids[row].length);
            Arrays.fill(mask[row], 0, ids[row].length, 1L);
        }
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(environment, padded));
            inputs.put("attention_mask", OnnxTensor.createTensor(environment, mask));
            if (wantsTokenTypes) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(environment, new long[names.size()][width]));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                List<float[]> vectors = new ArrayList<>(names.size());
                for (int row = 0; row < names.size(); row++) {
                    vectors.add(meanOfTokens(hidden[row], mask[row]));
                }
                return vectors;
            }
        } catch (OrtException exception) {
            throw new IllegalStateException("Model za značenje naziva nije uspeo", exception);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static float[] meanOfTokens(float[][] tokens, long[] mask) {
        float[] sum = new float[tokens[0].length];
        int count = 0;
        for (int token = 0; token < tokens.length; token++) {
            if (mask[token] == 0) {
                continue;
            }
            count++;
            for (int dimension = 0; dimension < sum.length; dimension++) {
                sum[dimension] += tokens[token][dimension];
            }
        }
        double length = 0;
        for (int dimension = 0; dimension < sum.length; dimension++) {
            sum[dimension] /= Math.max(count, 1);
            length += (double) sum[dimension] * sum[dimension];
        }
        length = Math.sqrt(length);
        for (int dimension = 0; dimension < sum.length; dimension++) {
            sum[dimension] = (float) (sum[dimension] / Math.max(length, 1e-9));
        }
        return sum;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
