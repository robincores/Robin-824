package io.github.robincores.toolchain.r8as;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight validation for architecture JSON specs.
 *
 * We intentionally keep this dependency-free (beyond Gson) instead of pulling
 * a JSON schema library.
 */
final class ArchSpecValidator {

    static final class ValidationError {
        final String path;
        final String message;
        ValidationError(String path, String message) {
            this.path = path;
            this.message = message;
        }
        @Override public String toString() { return path + ": " + message; }
    }

    static List<ValidationError> validate(JsonObject root) {
        List<ValidationError> errs = new ArrayList<>();
        requireString(root, "$.name", "name", errs);
        requireNumber(root, "$.width", "width", errs);

        JsonObject vars = requireObject(root, "$.vars", "vars", errs);
        if (vars != null) {
            for (Map.Entry<String, JsonElement> e : vars.entrySet()) {
                String vname = e.getKey();
                JsonObject vobj = requireObject(vars, "$.vars." + vname, vname, errs);
                if (vobj == null) continue;
                requireNumber(vobj, "$.vars." + vname + ".bits", "bits", errs);

                // optional fields
                if (vobj.has("toks")) {
                    JsonArray toks = requireArray(vobj, "$.vars." + vname + ".toks", "toks", errs);
                    if (toks != null) {
                        for (int i = 0; i < toks.size(); i++) {
                            JsonElement te = toks.get(i);
                            if (!isString(te)) {
                                errs.add(new ValidationError("$.vars." + vname + ".toks[" + i + "]", "must be a string"));
                            }
                        }
                    }
                }

                if (vobj.has("endian")) {
                    String endian = asString(vobj.get("endian"));
                    if (endian == null || !(endian.equals("little") || endian.equals("big") || endian.equals("msb"))) {
                        errs.add(new ValidationError("$.vars." + vname + ".endian", "must be one of: little, big"));
                    }
                }
                if (vobj.has("iprel") && !isBoolean(vobj.get("iprel"))) {
                    errs.add(new ValidationError("$.vars." + vname + ".iprel", "must be boolean"));
                }
                if (vobj.has("ipofs") && !isNumber(vobj.get("ipofs"))) {
                    errs.add(new ValidationError("$.vars." + vname + ".ipofs", "must be a number"));
                }
                if (vobj.has("ipmul") && !isNumber(vobj.get("ipmul"))) {
                    errs.add(new ValidationError("$.vars." + vname + ".ipmul", "must be a number"));
                }
            }
        }

        JsonArray rules = requireArray(root, "$.rules", "rules", errs);
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                JsonElement re = rules.get(i);
                if (!re.isJsonObject()) {
                    errs.add(new ValidationError("$.rules[" + i + "]", "must be an object"));
                    continue;
                }
                JsonObject robj = re.getAsJsonObject();
                requireString(robj, "$.rules[" + i + "].fmt", "fmt", errs);
                JsonArray bits = requireArray(robj, "$.rules[" + i + "].bits", "bits", errs);
                if (bits != null) {
                    for (int j = 0; j < bits.size(); j++) {
                        JsonElement be = bits.get(j);
                        String bpath = "$.rules[" + i + "].bits[" + j + "]";
                        if (isString(be) || isNumber(be)) {
                            continue;
                        }
                        if (be != null && be.isJsonObject()) {
                            JsonObject so = be.getAsJsonObject();
                            if (!so.has("a") || !isNumber(so.get("a"))) errs.add(new ValidationError(bpath + ".a", "must be a number"));
                            if (!so.has("b") || !isNumber(so.get("b"))) errs.add(new ValidationError(bpath + ".b", "must be a number"));
                            if (!so.has("n") || !isNumber(so.get("n"))) errs.add(new ValidationError(bpath + ".n", "must be a number"));
                            continue;
                        }
                        errs.add(new ValidationError(bpath, "must be string, number, or slice object {a,b,n}"));
                    }
                }
            }
        }

        return errs;
    }

    // ---- helpers ----

    private static JsonObject requireObject(JsonObject obj, String path, String key, List<ValidationError> errs) {
        if (obj == null) {
            errs.add(new ValidationError(path, "must be an object"));
            return null;
        }
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonObject()) {
            errs.add(new ValidationError(path, "must be an object"));
            return null;
        }
        return e.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject obj, String path, String key, List<ValidationError> errs) {
        if (obj == null) {
            errs.add(new ValidationError(path, "must be an array"));
            return null;
        }
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonArray()) {
            errs.add(new ValidationError(path, "must be an array"));
            return null;
        }
        return e.getAsJsonArray();
    }

    private static void requireString(JsonObject obj, String path, String key, List<ValidationError> errs) {
        if (obj == null) {
            errs.add(new ValidationError(path, "must be a string"));
            return;
        }
        JsonElement e = obj.get(key);
        if (!isString(e)) errs.add(new ValidationError(path, "must be a string"));
    }

    private static void requireNumber(JsonObject obj, String path, String key, List<ValidationError> errs) {
        if (obj == null) {
            errs.add(new ValidationError(path, "must be a number"));
            return;
        }
        JsonElement e = obj.get(key);
        if (!isNumber(e)) errs.add(new ValidationError(path, "must be a number"));
    }

    private static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static boolean isBoolean(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
    }

    private static String asString(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : null;
    }

    private ArchSpecValidator() {}
}
