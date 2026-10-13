package io.github.muness.roonpackager.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.FileInputStream;

public final class RoonPolicyTest {
    private Catalogue shipped() throws Exception {
        try (FileInputStream in = new FileInputStream("src/main/assets/catalogue.json")) {
            return Catalogue.read(in);
        }
    }
    private void rejected(Catalogue c) {
        try { RoonPolicy.validate(c); fail("Unsafe override accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void bundledCatalogueMeetsPolicy() throws Exception {
        RoonPolicy.validate(shipped());
    }
    @Test public void anotherEndpointIsRejected() throws Exception {
        Catalogue c = shipped(); c.endpoints.add(c.endpoints.get(0)); rejected(c);
    }
    @Test public void redirectedVendorSourceIsRejected() throws Exception {
        Catalogue c = shipped(); c.byId("roon").vendorArchive.urlPattern = "https://example.com/builds/RoonBridge_linuxarmv8.tar.bz2"; rejected(c);
    }
    @Test public void firstUsePinningIsRejected() throws Exception {
        Catalogue c = shipped(); c.byId("roon").vendorArchive.pinOnFirstUse = true; rejected(c);
    }
    @Test public void malformedHashIsRejected() throws Exception {
        Catalogue c = shipped(); c.byId("roon").shell.sha256 = "abc"; rejected(c);
    }
    @Test public void cleartextShellIsRejected() throws Exception {
        Catalogue c = shipped(); c.byId("roon").shell.url = "http://192.168.1.1/shell.apk"; rejected(c);
    }
    @Test public void localVendorOverrideIsRejected() throws Exception {
        Catalogue c = shipped(); c.byId("roon").vendorArchive.urlPattern = "file:/tmp/vendor.tar.bz2"; rejected(c);
    }
}
