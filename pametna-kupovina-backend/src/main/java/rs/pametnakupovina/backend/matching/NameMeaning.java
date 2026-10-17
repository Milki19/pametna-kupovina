package rs.pametnakupovina.backend.matching;

import java.util.List;

/**
 * What a product name means, as a unit vector: "KOB PILI 500G" and "Pileća
 * kobasica 0,5 kg" come out close although they share few letters. The dot
 * product of two vectors is their similarity (1 is the same meaning).
 */
public interface NameMeaning {

    /** False when no model is configured or it did not load; nothing is scored then. */
    boolean available();

    /** Which model made a vector, so vectors of another model are made again. */
    String modelCode();

    List<float[]> vectors(List<String> names);

    static NameMeaning unavailable() {
        return new NameMeaning() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String modelCode() {
                return "";
            }

            @Override
            public List<float[]> vectors(List<String> names) {
                throw new IllegalStateException("Model za značenje naziva nije učitan");
            }
        };
    }
}
