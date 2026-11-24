package com.intellitwin.core;

/** Advisory actions only: IntelliTwin never controls machines. */
public enum Recommendation {
    CONTINUE_MONITORING(0), INSPECT_MACHINE(1), SCHEDULE_MAINTENANCE(2), IMMEDIATE_INTERVENTION(3);

    private final int rank;

    Recommendation(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    public static Recommendation ofRank(int rank) {
        for (Recommendation r : values()) if (r.rank == rank) return r;
        throw new IllegalArgumentException("rank " + rank);
    }
}
