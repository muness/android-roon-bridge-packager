package io.github.muness.roonpackager.core;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.security.SignatureException;
import java.util.Collections;

/**
 * Signing, with the library {@code apksigner} is built from.
 *
 * <p>{@code apksig} runs on Android. That single fact is what makes this app
 * possible at all, and it is the same reason ReVanced can do its work on a
 * phone: there is no need for a JDK, a build-tools install, or a desktop.
 *
 * <p>v2 and v3 only, v1 off, exactly as both desktop packers invoke
 * {@code apksigner}. Alignment is preserved rather than redone, because
 * {@link ZipWriter} already aligned every stored entry to what the catalogue
 * asked for -- which is what the desktop packers' {@code zipalign} step
 * produces, and what the parity check compares.
 */
public final class ApkSigning {

    private ApkSigning() {
    }

    /** Signs {@code unsigned} into {@code signed}. */
    public static void sign(File unsigned, File signed, SigningKey key, int minSdk)
            throws IOException {
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                SigningKey.ALIAS, key.privateKey, key.certificates).build();
        ApkSigner apkSigner = new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setAlignmentPreserved(true)
                // A shell built as a debug APK is debuggable, and signing one
                // is the normal case here rather than a mistake.
                .setDebuggableApkPermitted(true)
                .setCreatedBy("android-roon-packager")
                .build();
        try {
            apkSigner.sign();
        } catch (ApkFormatException | NoSuchAlgorithmException | InvalidKeyException
                | SignatureException error) {
            throw new IOException("could not sign " + signed.getName() + ": "
                    + error.getMessage(), error);
        }
    }

    /** What the signature says, or why it does not verify. */
    public static String verify(File apk, int minSdk) throws IOException {
        try {
            ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                    .setMinCheckedPlatformVersion(minSdk)
                    .build()
                    .verify();
            if (!result.isVerified()) {
                StringBuilder text = new StringBuilder("the signature does not verify:");
                for (ApkVerifier.IssueWithParams error : result.getErrors()) {
                    text.append("\n  ").append(error);
                }
                throw new IOException(text.toString());
            }
            StringBuilder text = new StringBuilder();
            text.append("v2 ").append(result.isVerifiedUsingV2Scheme())
                    .append(", v3 ").append(result.isVerifiedUsingV3Scheme());
            for (ApkVerifier.Result.V3SchemeSignerInfo signer : result.getV3SchemeSigners()) {
                text.append("\n  signer: ")
                        .append(signer.getCertificate().getSubjectX500Principal().getName());
            }
            if (result.getV3SchemeSigners().isEmpty()) {
                for (ApkVerifier.Result.V2SchemeSignerInfo signer : result.getV2SchemeSigners()) {
                    text.append("\n  signer: ")
                            .append(signer.getCertificate().getSubjectX500Principal().getName());
                }
            }
            return text.toString();
        } catch (ApkFormatException | NoSuchAlgorithmException error) {
            throw new IOException("cannot verify " + apk.getName() + ": " + error.getMessage(),
                    error);
        }
    }
}
