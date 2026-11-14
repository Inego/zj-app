package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;

/**
 * The browser's UTC offset, in minutes, at an instant.
 *
 * <p>TeaVM has no zone database of its own, so the offset is read from {@code Intl}: the
 * instant is formatted in the browser's own zone and compared with the same instant read
 * as UTC. The difference is the offset in force <em>at that instant</em>, so a daylight
 * saving change is seen at the right moment instead of being frozen at the zone's
 * standard offset.
 *
 * <p>The hour is read as {@code 24} by some engines at midnight; that is folded back to
 * {@code 0} before the comparison. A browser without {@code Intl} reports {@code 0},
 * which is UTC — the clock the rating chart's axis already uses.
 */
public final class NativeLocalTime {
    private NativeLocalTime() {}

    @JSBody(params = {"epochMs"}, script = """
        try {
            var zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
            if (!zone) return 0;
            var parts = new Intl.DateTimeFormat('en-US', {
                timeZone: zone,
                hour12: false,
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            }).formatToParts(new Date(epochMs));
            var year = 1970, month = 1, day = 1, hour = 0, minute = 0, second = 0;
            for (var i = 0; i < parts.length; i++) {
                var part = parts[i];
                var value = parseInt(part.value, 10);
                if (isNaN(value)) continue;
                if (part.type === 'year') year = value;
                else if (part.type === 'month') month = value;
                else if (part.type === 'day') day = value;
                else if (part.type === 'hour') hour = value;
                else if (part.type === 'minute') minute = value;
                else if (part.type === 'second') second = value;
            }
            if (hour === 24) hour = 0;
            var asUtc = Date.UTC(year, month - 1, day, hour, minute, second);
            return Math.round((asUtc - epochMs) / 60000);
        } catch (e) {
            return 0;
        }
    """)
    public static native int offsetMinutes(double epochMs);
}
