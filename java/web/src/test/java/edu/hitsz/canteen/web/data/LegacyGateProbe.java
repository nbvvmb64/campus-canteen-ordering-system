package edu.hitsz.canteen.web.data;

import edu.hitsz.canteen.persistence.LegacyWriteGate;

import java.nio.file.Path;

/** Separate JVM used by the cross-process cutover-lock test. */
public final class LegacyGateProbe {
    public static void main(String[] args) {
        LegacyWriteGate.withCutoverLock(Path.of(args[0]), () -> {
            System.out.println("LOCKED");
            System.out.flush();
            try { Thread.sleep(1200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return null;
        });
    }
}
