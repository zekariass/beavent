package com.beavent.backend.scheduling.internal;

import com.beavent.backend.scheduling.internal.AvailabilityEngine.Interval;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small set arithmetic on half-open time intervals. */
final class Intervals {

    private Intervals() {}

    /** {@code base} with every part covered by {@code cuts} removed; the result is sorted and has no empty parts. */
    static List<Interval> minus(List<Interval> base, List<Interval> cuts) {
        List<Interval> sortedCuts =
                cuts.stream().sorted(Comparator.comparing(Interval::start)).toList();
        List<Interval> result = new ArrayList<>();
        for (Interval piece :
                base.stream().sorted(Comparator.comparing(Interval::start)).toList()) {
            var start = piece.start();
            for (Interval cut : sortedCuts) {
                if (!cut.end().isAfter(start) || !cut.start().isBefore(piece.end())) {
                    continue;
                }
                if (cut.start().isAfter(start)) {
                    result.add(new Interval(start, cut.start()));
                }
                if (cut.end().isAfter(start)) {
                    start = cut.end();
                }
                if (!start.isBefore(piece.end())) {
                    break;
                }
            }
            if (start.isBefore(piece.end())) {
                result.add(new Interval(start, piece.end()));
            }
        }
        return result;
    }
}
