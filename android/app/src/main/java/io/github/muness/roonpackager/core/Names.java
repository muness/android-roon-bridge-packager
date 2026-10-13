package io.github.muness.roonpackager.core;

/**
 * The name a file out of a vendor archive is carried under inside the APK.
 *
 * <p>This is the same rule {@code scripts/pack-roon-apk.sh} implements in
 * shell and {@code RoonNames.java} implements in the Roon app, ported once so
 * the on-device packer cannot drift from either:
 *
 * <ul>
 *   <li>a name Android already extracts -- {@code lib*.so} with exactly one
 *       dot -- is kept exactly as it is, because the .NET host
 *       {@code realpath()}s the {@code libcoreclr.so} it found and then
 *       appends that literal name back onto the directory it landed in;</li>
 *   <li>anything else becomes {@code lib<core>_roon.so}: a trailing
 *       {@code .so.N} folds to {@code .N}, a trailing {@code .so} is dropped,
 *       a leading {@code lib} is dropped, every character that is not
 *       {@code [A-Za-z0-9_]} becomes {@code _}, and the result is wrapped --
 *       one dot, which is all Android extracts.</li>
 * </ul>
 *
 * <p>Why any of it: Android will only map a file executable out of an app's own
 * {@code nativeLibraryDir}, and the installer only extracts an entry into that
 * directory when the entry's name matches {@code lib*.so} with a single dot. A
 * two-dot name is silently not extracted, which is a failure with no error
 * message, so the packer checks the rule and the verifier checks it again.
 */
public final class Names {

    private Names() {
    }

    /** How many dots are in a name. Two is the failure this rule avoids. */
    public static int dotCount(String name) {
        int dots = 0;
        for (int index = 0; index < name.length(); index++) {
            if (name.charAt(index) == '.') {
                dots++;
            }
        }
        return dots;
    }

    /** {@code lib*.so} with exactly one dot: what Android extracts, unchanged. */
    public static boolean extractable(String name) {
        return name.length() > "lib.so".length()
                && name.startsWith("lib")
                && name.endsWith(".so")
                && dotCount(name) == 1;
    }

    /**
     * The APK entry name (without the {@code lib/arm64-v8a/} prefix) for an
     * executable archive member called {@code base}.
     *
     * @param suffix the wrapper this endpoint uses for names it had to invent,
     *               e.g. {@code _roon.so} or {@code _exec.so}
     */
    public static String packaged(String base, String suffix) {
        if (extractable(base)) {
            return base;
        }
        String core = base;
        int soDot = core.indexOf(".so.");
        if (soDot >= 0) {
            core = core.substring(0, soDot) + "." + core.substring(soDot + ".so.".length());
        } else if (core.endsWith(".so")) {
            core = core.substring(0, core.length() - ".so".length());
        }
        if (core.startsWith("lib") && core.length() > 3) {
            core = core.substring(3);
        }
        StringBuilder cleaned = new StringBuilder(core.length());
        for (int index = 0; index < core.length(); index++) {
            char character = core.charAt(index);
            boolean plain = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_';
            cleaned.append(plain ? character : '_');
        }
        if (cleaned.length() == 0) {
            throw new IllegalArgumentException("nothing left of the name " + base);
        }
        return "lib" + cleaned + suffix;
    }
}
