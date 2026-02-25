package io.github.robincores.toolchain.r8as;

public class Symbol {
    final int value;          // word address at definition time (ip)
    final String section;     // e.g. ".text", ".data" ; null => absolute

    Symbol(int value) {
        this(value, null);
    }

    Symbol(int value, String section) {
        this.value = value;
        this.section = section;
    }

    @Override
    public String toString() {
        return "Symbol{" +
                "value=" + value +
                ", section='" + section + '\'' +
                '}';
    }
}
