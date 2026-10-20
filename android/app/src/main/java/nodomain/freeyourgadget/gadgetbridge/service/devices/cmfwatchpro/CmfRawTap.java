/*  SLEEPWAKE FORK: new file, AGPL-3.0-or-later like Gadgetbridge.
 *
 *  The CMF driver reads 16 bytes per activity record that it does not understand and throws them away.
 *  They may contain movement data. This tap lets another component (the sleepwake module) see them.
 *  Without a listener it does nothing.
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro;

public final class CmfRawTap {
    public interface Listener {
        /** One 32 byte activity record: timestamp (s), steps (cumulative), distance, calories and the 16 unknown bytes. */
        void onActivityRecord(int timestamp, int steps, int distance, int calories, byte[] unknown);
    }

    private static volatile Listener listener;

    private CmfRawTap() {
    }

    public static void setListener(final Listener l) {
        listener = l;
    }

    static void activityRecord(final int timestamp, final int steps, final int distance, final int calories, final byte[] unknown) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onActivityRecord(timestamp, steps, distance, calories, unknown);
        } catch (final Exception ignored) {
            // never let the tap break the driver
        }
    }
}
