package io.github.robincores.toolchain.r8as;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchSpecValidatorTest {

    @Test
    void valid_spec_has_no_errors() {
        JsonObject root = JsonParser.parseString("""
                {
                  "name": "toy",
                  "width": 8,
                  "vars": {
                    "imm8": {"bits": 8, "iprel": false, "ipofs": 0, "ipmul": 1, "endian": "little"},
                    "reg": {"bits": 4, "toks": ["r0", "r1"]}
                  },
                  "rules": [
                    {"fmt": "nop", "bits": [0]},
                    {"fmt": "b ~imm8", "bits": [1, {"a":0, "b":0, "n":8}]}
                  ]
                }
                """).getAsJsonObject();

        List<ArchSpecValidator.ValidationError> errs = ArchSpecValidator.validate(root);
        assertTrue(errs.isEmpty(), () -> "expected no validation errors, got: " + errs);
    }

    @Test
    void missing_required_fields_are_reported() {
        JsonObject root = JsonParser.parseString("{}") .getAsJsonObject();
        List<ArchSpecValidator.ValidationError> errs = ArchSpecValidator.validate(root);
        assertFalse(errs.isEmpty());
        String joined = errs.toString();
        assertTrue(joined.contains("$.name"));
        assertTrue(joined.contains("$.width"));
        assertTrue(joined.contains("$.vars"));
        assertTrue(joined.contains("$.rules"));
    }

    @Test
    void malformed_var_and_rule_fields_are_reported() {
        JsonObject root = JsonParser.parseString("""
                {
                  "name": "toy",
                  "width": 8,
                  "vars": {
                    "imm": {"bits": 8, "endian": "middle", "iprel": "yes", "ipofs": "x", "ipmul": []},
                    "reg": {"bits": 2, "toks": [0, "r1"]}
                  },
                  "rules": [
                    {"fmt": "mov ~reg,~imm", "bits": [false, {"a":0, "b":"x", "n":1}]}
                  ]
                }
                """).getAsJsonObject();

        List<ArchSpecValidator.ValidationError> errs = ArchSpecValidator.validate(root);
        assertFalse(errs.isEmpty());
        String joined = errs.toString();
        assertTrue(joined.contains("$.vars.imm.endian"));
        assertTrue(joined.contains("$.vars.imm.iprel"));
        assertTrue(joined.contains("$.vars.imm.ipofs"));
        assertTrue(joined.contains("$.vars.imm.ipmul"));
        assertTrue(joined.contains("$.vars.reg.toks[0]"));
        assertTrue(joined.contains("$.rules[0].bits[0]"));
        assertTrue(joined.contains("$.rules[0].bits[1].b"));
    }
}
