package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    private val packages = listOf("com.ivendapp", "com.example.vmivendappupdater")
    @Test fun emptyPolicyOnlyTargetsManagedApps() {
        assertEquals("immersive.navigation=com.ivendapp,com.example.vmivendappupdater",
            NavigationPolicy.merge("null", packages))
    }
    @Test fun keepsOtherPoliciesAndIsIdempotent() {
        val merged = NavigationPolicy.merge("immersive.status=other.app:immersive.navigation=third.app", packages)
        assertEquals("immersive.status=other.app:immersive.navigation=third.app,com.ivendapp,com.example.vmivendappupdater", merged)
        assertEquals(merged, NavigationPolicy.merge(merged, packages))
    }
    @Test fun replacesOnlyManagedAppExclusion() {
        assertEquals("immersive.navigation=-other.app,com.ivendapp,com.example.vmivendappupdater",
            NavigationPolicy.merge("immersive.navigation=-com.ivendapp,-other.app", packages))
    }
}
