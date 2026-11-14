package edu.dau.practolite.common.model;

import java.util.Locale;

/**
 * Keys of the sparse "search-index" GSI on the Doctors table. Shared because two services write them:
 * the Users service (admin verification) and the Doctors service (profile updates of a verified doctor).
 *
 * <p>Only verified doctors have these attributes, so only verified doctors appear in the index.
 */
public final class DoctorSearchKeys {

    public static final String SEARCH_CITY = "searchCity";
    public static final String SPEC_KEY = "specKey";
    public static final String INDEX_NAME = "search-index";

    private DoctorSearchKeys() {
    }

    /** "  New Delhi " becomes "new delhi". */
    public static String searchCity(String city) {
        return normalise(city);
    }

    /** "Cardiology" + "Dr Ravi Shah" becomes "cardiology#dr ravi shah". */
    public static String specKey(String speciality, String name) {
        return normalise(speciality) + "#" + normalise(name);
    }

    /** Prefix for begins_with on specKey when searching by speciality. */
    public static String specPrefix(String speciality) {
        return normalise(speciality) + "#";
    }

    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
