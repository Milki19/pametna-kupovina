package rs.pametnakupovina.backend.matching;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The SentencePiece unigram tokenizer of XLM-RoBERTa models (multilingual-e5),
 * read from the model's tokenizer.json: NFKC, "▁" for every space, then the
 * most probable split of each word into pieces. Gives the same ids as the
 * Hugging Face tokenizer for product names, without a native library.
 */
public final class UnigramTokenizer {

    private static final char SPACE = '▁';
    // Hugging Face scores a character no piece covers this far below the worst piece.
    private static final double UNKNOWN_PENALTY = 10.0;

    private final Map<String, Integer> ids;
    private final double[] scores;
    private final int maxPieceLength;
    private final int unknownId;
    private final double unknownScore;
    private final int startId;
    private final int endId;
    private final int padId;

    UnigramTokenizer(List<String> pieces, List<Double> pieceScores, int unknownId) {
        this.ids = new HashMap<>(pieces.size() * 2);
        this.scores = new double[pieces.size()];
        int longest = 1;
        double worst = 0;
        for (int index = 0; index < pieces.size(); index++) {
            ids.putIfAbsent(pieces.get(index), index);
            scores[index] = pieceScores.get(index);
            longest = Math.max(longest, pieces.get(index).length());
            worst = Math.min(worst, scores[index]);
        }
        this.maxPieceLength = longest;
        this.unknownId = unknownId;
        this.unknownScore = worst - UNKNOWN_PENALTY;
        this.startId = ids.getOrDefault("<s>", 0);
        this.endId = ids.getOrDefault("</s>", 2);
        this.padId = ids.getOrDefault("<pad>", 1);
    }

    public static UnigramTokenizer read(InputStream tokenizerJson) throws IOException {
        JsonNode model = new ObjectMapper().readTree(tokenizerJson).path("model");
        if (!"Unigram".equals(model.path("type").asText())) {
            throw new IOException("tokenizer.json nije Unigram model");
        }
        List<String> pieces = new ArrayList<>();
        List<Double> pieceScores = new ArrayList<>();
        for (JsonNode entry : model.path("vocab")) {
            pieces.add(entry.get(0).asText());
            pieceScores.add(entry.get(1).asDouble());
        }
        return new UnigramTokenizer(pieces, pieceScores, model.path("unk_id").asInt(3));
    }

    public int padId() {
        return padId;
    }

    /** {@code <s>} pieces {@code </s>}, cut to at most maxLength ids. */
    public long[] encode(String text, int maxLength) {
        List<Integer> pieces = new ArrayList<>();
        for (String word : words(text)) {
            pieces.addAll(split(word));
        }
        int kept = Math.min(pieces.size(), Math.max(0, maxLength - 2));
        long[] result = new long[kept + 2];
        result[0] = startId;
        for (int index = 0; index < kept; index++) {
            result[index + 1] = pieces.get(index);
        }
        result[kept + 1] = endId;
        return result;
    }

    /** NFKC, one space for a run of them, and "▁" before every word. */
    static List<String> words(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replaceAll(" {2,}", " ")
                .replace(' ', SPACE);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.charAt(0) != SPACE) {
            normalized = SPACE + normalized;
        }
        List<String> words = new ArrayList<>();
        int start = 0;
        for (int index = 1; index < normalized.length(); index++) {
            if (normalized.charAt(index) == SPACE) {
                words.add(normalized.substring(start, index));
                start = index;
            }
        }
        words.add(normalized.substring(start));
        return words;
    }

    /** The split with the highest total score (Viterbi); unknown characters join into one unknown piece. */
    List<Integer> split(String word) {
        int length = word.length();
        double[] best = new double[length + 1];
        int[] from = new int[length + 1];
        int[] piece = new int[length + 1];
        Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int start = 0; start < length; start++) {
            if (best[start] == Double.NEGATIVE_INFINITY) {
                continue;
            }
            int charLength = Character.charCount(word.codePointAt(start));
            boolean singleCharacterPiece = false;
            for (int end = start + 1; end <= Math.min(length, start + maxPieceLength); end++) {
                Integer id = ids.get(word.substring(start, end));
                if (id == null) {
                    continue;
                }
                if (end - start == charLength) {
                    singleCharacterPiece = true;
                }
                double score = best[start] + scores[id];
                if (score > best[end]) {
                    best[end] = score;
                    from[end] = start;
                    piece[end] = id;
                }
            }
            if (!singleCharacterPiece) {
                int end = start + charLength;
                double score = best[start] + unknownScore;
                if (score > best[end]) {
                    best[end] = score;
                    from[end] = start;
                    piece[end] = unknownId;
                }
            }
        }
        List<Integer> reversed = new ArrayList<>();
        for (int end = length; end > 0; end = from[end]) {
            int id = piece[end];
            if (id == unknownId && !reversed.isEmpty() && reversed.get(reversed.size() - 1) == unknownId) {
                continue;
            }
            reversed.add(id);
        }
        List<Integer> result = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            result.add(reversed.get(index));
        }
        return result;
    }
}
