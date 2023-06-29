package org.e2k;

public enum RivetMode {
    // the order of values is important, should match MODENAMES in Rivet.java
    CROWD36("CROWD36"),
    XPA10("XPA 10baud"),
    XPA2("XPA2"),
    XPA20("XPA 20 baud"),
    Experimental_dontuse("Experimental something"),
    CIS36_50("CIS36-50"),
    F01("F01"),
    CCIR493_4("CCIR493-4"),
    F06("F06"),
    GWFSK("GW FSK"),
    RTTY("RTTY Baudot"),
    FSK("FSK raw"),
    F06a("F06a");

    public final String label;

    RivetMode(String label) {
        this.label = label;
    }
}
