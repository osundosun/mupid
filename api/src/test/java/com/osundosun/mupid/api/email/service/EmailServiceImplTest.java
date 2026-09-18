package com.osundosun.mupid.api.email.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmailServiceImplTest {

    @Test
    void mailLogoUrlUsesDeploymentRootWithoutDuplicatingSlash() {
        assertEquals(
            "https://j15a204.p.ssafy.io/mupid-logo-email.png",
            EmailServiceImpl.buildMailLogoUrl("https://j15a204.p.ssafy.io/")
        );
    }

    @Test
    void mailLogoUrlDoesNotInheritApiPathOrQuery() {
        assertEquals(
            "https://j15a204.p.ssafy.io/mupid-logo-email.png",
            EmailServiceImpl.buildMailLogoUrl("https://j15a204.p.ssafy.io/api?source=mail")
        );
    }
}
