package io.github.muness.roonpackager.core;

/**
 * The step names the progress view shows. They are the real steps, in the real
 * order, because a progress bar that says "working" while a phone spends forty
 * seconds inflating a tarball tells the person holding it nothing.
 */
public interface PackProgress {

    enum Step {
        DOWNLOADING_SHELL("downloading the app shell"),
        DOWNLOADING_VENDOR("downloading the vendor archive"),
        VERIFYING_DOWNLOAD("verifying the download"),
        EXTRACTING("extracting"),
        PACKING("packing"),
        SIGNING("signing"),
        VERIFYING_RESULT("verifying the result"),
        INSTALLING("installing");

        public final String label;

        Step(String label) {
            this.label = label;
        }
    }

    /** A step has begun. */
    void step(Step step, String detail);

    /**
     * Progress inside a step, or {@code total <= 0} when the size is unknown.
     * Called often; must not block.
     */
    void bytes(long done, long total);

    /** A line for the log and the diagnostics endpoint. */
    void note(String line);

    PackProgress NONE = new PackProgress() {
        @Override
        public void step(Step step, String detail) {
        }

        @Override
        public void bytes(long done, long total) {
        }

        @Override
        public void note(String line) {
        }
    };
}
