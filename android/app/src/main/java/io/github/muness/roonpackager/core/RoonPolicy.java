package io.github.muness.roonpackager.core;

import java.net.URI;

/** Application boundary; generic packer fixtures are intentionally separate. */
public final class RoonPolicy {
    private RoonPolicy() {}

    public static Catalogue validate(Catalogue catalogue) {
        catalogue.validate();
        if (catalogue.endpoints.size() != 1) {
            throw new IllegalArgumentException("Only one Roon Bridge endpoint is supported");
        }
        Catalogue.Endpoint e = catalogue.endpoints.get(0);
        if (!"roon".equals(e.id) || !e.packPlan.isTree()
                || !"io.github.muness.roonbridge".equals(e.shell.packageId)) {
            throw new IllegalArgumentException("Only the Roon Bridge tree endpoint is supported");
        }
        URI vendor = https(e.vendorArchiveUrl());
        if (!"download.roonlabs.com".equals(vendor.getHost())
                || !"/builds/RoonBridge_linuxarmv8.tar.bz2".equals(vendor.getPath())
                || vendor.getQuery() != null || vendor.getPort() != -1) {
            throw new IllegalArgumentException("Roon downloads must use the official ARMv8 URL");
        }
        if (e.vendorArchive.pinOnFirstUse) {
            throw new IllegalArgumentException("Unpinned Roon downloads are not allowed");
        }
        hash(e.vendorArchiveSha256());
        https(e.shell.url);
        hash(e.shell.sha256);
        for (Catalogue.Supplement s : e.packPlan.supplements) {
            https(s.url);
            hash(s.sha256);
        }
        return catalogue;
    }

    private static URI https(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Download URLs must use HTTPS without credentials");
        }
        return uri;
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Every input requires a SHA-256 pin");
        }
    }
}
